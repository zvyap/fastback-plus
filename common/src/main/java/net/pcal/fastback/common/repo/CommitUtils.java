/*
 * FastBack - Fast, incremental Minecraft backups powered by Git.
 * Copyright (C) 2022 pcal.net
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; If not, see <http://www.gnu.org/licenses/>.
 */

package net.pcal.fastback.common.repo;

import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.utils.EnvironmentUtils;
import net.pcal.fastback.common.utils.Executor;
import net.pcal.fastback.common.utils.ProcessException;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static net.pcal.fastback.common.config.FastbackConfigKey.IS_MODS_BACKUP_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_NATIVE_GIT_ENABLED;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.JGIT;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.NATIVE_GIT;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.NORMAL;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.logging.UserMessage.styledRaw;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.repo.RepoImpl.FASTBACK_DIR;
import static net.pcal.fastback.common.utils.ProcessUtils.doExec;

/**
 * Utilities for adding and committing snapshots.
 *
 * @author pcal
 * @since 0.13.0
 */
abstract class CommitUtils {

    static SnapshotId doCommitSnapshot(final RepoImpl repo, final UserLogger ulog) throws IOException, ProcessException, GitAPIException {
        return doCommitSnapshot(repo, ulog, SnapshotMetadata.AUTOMATIC);
    }

    static SnapshotId doCommitSnapshot(final RepoImpl repo, final UserLogger ulog, final SnapshotMetadata metadata) throws IOException, ProcessException, GitAPIException {
        Executor.checkCancelled();
        PreflightUtils.doPreflight(repo);
        final WorldId uuid = repo.getWorldId();
        final GitConfig conf = repo.getConfig();
        final SnapshotId newSid = repo.getSidCodec().create(uuid);
        syslog().debug("start doCommitSnapshot for " + newSid);
        writeBackupProperties(repo);

        if (conf.getBoolean(IS_MODS_BACKUP_ENABLED)) {
            doSettingsBackup(repo, ulog);
        }
        Executor.checkCancelled();

        final String newBranchName = newSid.getBranchName();
        final String commitMessage = metadata.toCommitMessage(newBranchName);
        try {
            if (conf.getBoolean(IS_NATIVE_GIT_ENABLED)) {
                ulog.message(styledLocalized("fastback.chat.commit-start", NATIVE_GIT, newSid.getShortName()));
                native_commit(newBranchName, commitMessage, repo, ulog);
            } else {
                ulog.message(styledLocalized("fastback.chat.commit-start", NORMAL, newSid.getShortName()));
                jgit_commit(newBranchName, commitMessage, repo.getJGit(), ulog);
            }
        } catch (GitAPIException e) {
            Executor.checkCancelled();
            throw new IOException(e);
        } finally {
            SnapshotCache.invalidate(repo.getWorkTree().toPath());
        }
        Executor.checkCancelled();
        syslog().debug("Local backup complete.");
        return newSid;
    }

    private static void doSettingsBackup(RepoImpl repo, UserLogger ulog) {
        syslog().info("Backing up minecraft settings");
        try {
            final File backupDir = repo.getDotFasbackDir().resolve("mods-backup").toFile();
            if (backupDir.exists()) FileUtils.deleteDirectory(backupDir);
            backupDir.mkdirs();
            for (Path src : mod().getModsBackupPaths()) {
                Executor.checkCancelled();
                try {
                    final File srcFile = src.toFile();
                    syslog().debug("backing up " + srcFile + " to " + backupDir);
                    if (srcFile.exists()) {
                        if (srcFile.isDirectory()) {
                            FileUtils.copyDirectory(srcFile,
                                    backupDir.toPath().resolve(srcFile.getName()).toFile());
                        } else {
                            FileUtils.copyFile(srcFile, backupDir);
                        }
                    }
                } catch (Exception ohwell) {
                    Executor.checkCancelled();
                    syslog().error(ohwell);
                }
            }
        } catch (Exception ohwell) {
            Executor.checkCancelled();
            syslog().error(ohwell);
        }
    }

    private static void native_commit(final String newBranchName, final String commitMessage, final Repo repo, final UserLogger ulog) throws ProcessException, IOException {
        syslog().debug("Start native_commit");
        ulog.update(styledLocalized("fastback.hud.local-saving", NATIVE_GIT));
        final File worktree = repo.getWorkTree();
        final Map<String, String> env = Map.of("GIT_LFS_FORCE_PROGRESS", "1");
        final Consumer<String> outputConsumer = line -> ulog.update(styledRaw(line, NATIVE_GIT));
        String[] checkout = {"git", "-C", worktree.getAbsolutePath(), "checkout", "--orphan", newBranchName};
        doExec(checkout, env, outputConsumer, outputConsumer);
        mod().setWorldSaveEnabled(false);
        try {
            String[] add = {"git", "-C", worktree.getAbsolutePath(), "add", "-v", "."};
            doExec(add, env, outputConsumer, outputConsumer);
        } finally {
            mod().setWorldSaveEnabled(true);
            syslog().debug("World save re-enabled.");
        }
        nativeCommitMessage(repo, commitMessage, outputConsumer);
        syslog().debug("End native_commit");
    }

    static void nativeCommitMessage(final Repo repo, final String commitMessage, final Consumer<String> outputConsumer) throws IOException, ProcessException {
        // Keep the message outside the worktree index and avoid platform argument quoting of player remarks.
        final Path messageFile = Files.createTempFile(repo.getDirectory().toPath(), "fastback-commit-", ".txt");
        try {
            Files.writeString(messageFile, commitMessage, StandardCharsets.UTF_8);
            final String[] commit = {"git", "-C", repo.getWorkTree().getAbsolutePath(), "-c", "i18n.commitEncoding=UTF-8",
                    "commit", "--cleanup=verbatim", "-F", messageFile.toAbsolutePath().toString()};
            doExec(commit, Map.of("GIT_LFS_FORCE_PROGRESS", "1"), outputConsumer, outputConsumer);
        } finally {
            Files.deleteIfExists(messageFile);
        }
    }

    private static void jgit_commit(final String newBranchName, final String commitMessage, final Git jgit, final UserLogger ulog) throws GitAPIException, IOException {
        syslog().debug("Starting jgit_commit");
        Executor.checkCancelled();
        ulog.update(styledLocalized("fastback.hud.local-saving", JGIT));
        jgit.checkout().setOrphan(true).setName(newBranchName).call();
        jgit.reset().setMode(ResetCommand.ResetType.SOFT).call();
        syslog().debug("status");
        final Status status = jgit.status().call();
        Executor.checkCancelled();

        try {

            syslog().debug("Disabling world save for 'git add'");
            mod().setWorldSaveEnabled(false);


            //
            // Figure out what files to add and remove.  We don't just 'git add .' because this:
            // https://bugs.eclipse.org/bugs/show_bug.cgi?id=494323
            //
            {
                final List<String> toAdd = new ArrayList<>();
                toAdd.add(FASTBACK_DIR);
                toAdd.addAll(status.getModified());
                toAdd.addAll(status.getUntracked());
                Collections.sort(toAdd);
                if (!toAdd.isEmpty()) {
                    syslog().debug("Adding " + toAdd.size() + " new or modified files to index");

                    for (final String file : toAdd) {
                        Executor.checkCancelled();
                        final AddCommand gitAdd = jgit.add();
                        syslog().debug("add  " + file);
                        ulog.update(styledLocalized("fastback.chat.backup-start", JGIT, file));
                        gitAdd.addFilepattern(file);
                        gitAdd.call();
                    }
                }
            }
            {
                final List<String> toDelete = new ArrayList<>();
                toDelete.addAll(status.getRemoved());
                toDelete.addAll(status.getMissing());
                Collections.sort(toDelete);
                if (!toDelete.isEmpty()) {
                    syslog().debug("Removing " + toDelete.size() + " deleted files from index");
                    for (final String file : toDelete) {
                        Executor.checkCancelled();
                        final RmCommand gitRm = jgit.rm();
                        syslog().debug("rm  " + file);
                        ulog.update(styledLocalized("fastback.chat.backup-start", JGIT, file));
                        gitRm.addFilepattern(file);
                        gitRm.call();
                    }
                }
            }
        } finally {
            mod().setWorldSaveEnabled(true);
            syslog().debug("World save re-enabled.");
        }
        Executor.checkCancelled();
        syslog().debug("commit");
        ulog.update(styledLocalized("fastback.chat.commit-complete", JGIT));
        jgit.commit().setMessage(commitMessage).call();
    }

    private static void writeBackupProperties(Repo repo) throws IOException {
        final Map<String, String> props = new HashMap<>();
        GitConfig conf = repo.getConfig();
        props.put("fastback-" + IS_NATIVE_GIT_ENABLED.getSettingName(), conf.getString(IS_NATIVE_GIT_ENABLED));
        props.put("git-version", EnvironmentUtils.getGitVersion());
        props.put("git-lfs-version", EnvironmentUtils.getGitLfsVersion());
        try {
            mod().addBackupProperties(props);
        } catch (Exception e) {
            syslog().error("Failed to add extra backup.properties", e);
        }
        final Path path = repo.getWorkTree().toPath().resolve(FASTBACK_DIR + "/backup.properties");
        final List<String> keys = new ArrayList<>(props.keySet());
        try (final PrintWriter pw = new PrintWriter(new FileWriter(path.toFile()))) {
            Collections.sort(keys);
            for (String key : keys) {
                pw.println(key + " = " + props.get(key));
            }
        }
    }
}
