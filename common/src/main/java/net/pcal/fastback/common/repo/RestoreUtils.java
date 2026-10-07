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

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage.UserMessageStyle;
import net.pcal.fastback.common.utils.FileUtils;
import net.pcal.fastback.common.utils.LoadCountdown;
import net.pcal.fastback.common.utils.ProcessException;
import net.pcal.fastback.common.utils.ProcessUtils;
import net.pcal.fastback.common.utils.ServerWorldRestore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_NATIVE_GIT_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.LOAD_COUNTDOWN_SECONDS;
import static net.pcal.fastback.common.config.FastbackConfigKey.RESTORE_DIRECTORY;
import static net.pcal.fastback.common.config.OtherConfigKey.REMOTE_PUSH_URL;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.NATIVE_GIT;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.logging.UserMessage.styledRaw;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.utils.Executor.checkCancelled;

/**
 * Utilities for restoring a snapshot
 *
 * @author pcal
 * @since 0.13.0
 */
abstract class RestoreUtils {

    // ======================================================================
    // Package private

    static void doRestoreLocalSnapshot(final String snapshotNameToRestore, final RepoImpl repo, final UserLogger ulog) {
        doRestoreSnapshot(snapshotNameToRestore, mod().getWorldDirectory().toAbsolutePath().toUri().toString(), repo, ulog);
    }

    static void doRestoreRemoteSnapshot(final String snapshotNameToRestore, final RepoImpl repo, final UserLogger ulog) {
        final GitConfig conf = repo.getConfig();
        if (!conf.isSet(REMOTE_PUSH_URL)) {
            ulog.message(styledLocalized("fastback.chat.remote-no-url", ERROR));
        } else {
            doRestoreSnapshot(snapshotNameToRestore, conf.getString(REMOTE_PUSH_URL), repo, ulog);
        }
    }

    static void doLoadSnapshot(final String snapshotName, final boolean remote, final RepoImpl repo, final UserLogger ulog) {
        Path stagedWorld = null;
        boolean scheduled = false;
        try {
            PreflightUtils.doPreflight(repo);
            final GitConfig conf = repo.getConfig();
            if (remote && !conf.isSet(REMOTE_PUSH_URL)) {
                ulog.message(styledLocalized("fastback.chat.remote-no-url", ERROR));
                return;
            }
            final SnapshotId sid = repo.createSnapshotId(snapshotName);
            final Path world = mod().getWorldDirectory().toRealPath();
            final String repoUri = remote ? conf.getString(REMOTE_PUSH_URL) : world.toUri().toString();
            final int countdownSeconds = conf.getInt(LOAD_COUNTDOWN_SECONDS);
            if (countdownSeconds < 0) throw new IOException("Load countdown cannot be negative");
            checkCancelled();
            // A sibling keeps installation on the same filesystem, regardless of restore-directory.
            stagedWorld = Files.createTempDirectory(world.getParent(), ".fastback-load-");
            ulog.message(localized("fastback.chat.load-preparing", sid.getShortName()));
            final AtomicBoolean prepared = new AtomicBoolean();
            try (final LoadCountdown countdown = new LoadCountdown(countdownSeconds, seconds -> {
                if (seconds > 0) {
                    final var warning = localized("fastback.chat.load-countdown", sid.getShortName(), seconds);
                    ulog.message(warning);
                    mod().sendBroadcast(warning);
                } else if (!prepared.get()) {
                    ulog.message(localized("fastback.chat.load-waiting-preparation"));
                }
            })) {
                restoreSnapshot(sid.getBranchName(), stagedWorld, repoUri, conf, ulog);
                checkCancelled();
                // Keep the live repository's complete history and configuration during installation.
                if (Files.exists(stagedWorld.resolve(".git"))) FileUtils.rmdir(stagedWorld.resolve(".git"));
                ServerWorldRestore.validateSnapshot(stagedWorld);
                if (NbtIo.readCompressed(stagedWorld.resolve("level.dat"), NbtAccounter.create(64L * 1024 * 1024))
                        .getCompoundOrEmpty("Data").isEmpty()) {
                    throw new IOException("Restored level.dat does not contain world data");
                }
                if (!WorldIdUtils.getWorldIdInfo(stagedWorld).wid().equals(repo.getWorldId())) {
                    throw new IOException("Restored snapshot belongs to a different world");
                }
                prepared.set(true);
                countdown.await();
            }
            mod().requestServerRestore(stagedWorld);
            scheduled = true;
            checkCancelled();
            ulog.message(localized("fastback.chat.load-scheduled", sid.getShortName()));
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Snapshot load cancelled");
        } catch (Exception e) {
            checkCancelled();
            syslog().error("Server snapshot load failed before shutdown", e);
            ulog.message(styledLocalized("fastback.chat.load-failed", ERROR));
        } finally {
            if (!scheduled && stagedWorld != null) {
                try {
                    FileUtils.rmdir(stagedWorld);
                } catch (IOException e) {
                    syslog().error("Could not remove incomplete staged snapshot at " + stagedWorld, e);
                }
            }
        }
    }

    // ======================================================================
    // Private

    private static void doRestoreSnapshot(final String snapshotNameToRestore, final String repoUri, final RepoImpl repo, final UserLogger ulog) {
        try {
            PreflightUtils.doPreflight(repo);
            final GitConfig conf = repo.getConfig();
            final SnapshotId sid = repo.createSnapshotId(snapshotNameToRestore);
            final Path allRestoresDir = conf.isSet(RESTORE_DIRECTORY) ?
                    Paths.get(conf.getString(RESTORE_DIRECTORY)) : mod().getDefaultRestoresDir();
            final Path restoreTargetDir = getTargetDir(allRestoresDir, mod().getWorldName(), sid.getShortName());
            restoreSnapshot(sid.getBranchName(), restoreTargetDir, repoUri, conf, ulog);
            ulog.message(localized("fastback.chat.restore-done", restoreTargetDir));
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            checkCancelled();
            syslog().error(e);
            ulog.message(styledRaw("Restore failed.  See log for details.", ERROR)); // FIXME i18n
        }
    }

    private static void restoreSnapshot(final String branchName, final Path target, final String repoUri,
                                        final GitConfig conf, final UserLogger ulog) throws IOException, GitAPIException, ProcessException {
        if (conf.getBoolean(IS_NATIVE_GIT_ENABLED)) {
            native_restoreSnapshot(branchName, target, repoUri, ulog);
        } else {
            jgit_restoreSnapshot(branchName, target, repoUri, ulog);
        }
    }

    private static void native_restoreSnapshot(final String branchName, final Path restoreTargetDir, final String repoUri, final UserLogger ulog) throws ProcessException {
        final Map<String, String> env = Map.of("GIT_LFS_FORCE_PROGRESS", "1");
        final Consumer<String> outputConsumer = line -> ulog.update(styledRaw(line, NATIVE_GIT));
        final String restoreTargetDirStr = restoreTargetDir.toString();
        syslog().debug("Cloning repo at " + repoUri);
        ProcessUtils.doExec(new String[]{
                "git", "clone", repoUri, "--no-checkout", "--branch", branchName, "--single-branch", restoreTargetDirStr
        }, env, outputConsumer, outputConsumer);
        syslog().debug("Installing lfs locally in " + restoreTargetDirStr);
        ProcessUtils.doExec(new String[]{
                "git", "-C", restoreTargetDirStr, "lfs", "install", "--local"
        }, env, outputConsumer, outputConsumer);
        syslog().debug("Fetching lfs objects from local repo " + repoUri);
        ProcessUtils.doExec(new String[]{
                "git", "-C", restoreTargetDirStr, "lfs", "fetch", "--all", repoUri
        }, env, outputConsumer, outputConsumer);
        syslog().debug("Checking out " + branchName + ", downloading lfs blobs");
        ProcessUtils.doExec(new String[]{
                "git", "-C", restoreTargetDirStr, "checkout", branchName
        }, env, outputConsumer, outputConsumer);
    }

    private static void jgit_restoreSnapshot(final String branchName, final Path restoreTargetDir, final String repoUri, final UserLogger ulog) throws IOException, GitAPIException {
        ulog.update(localized("fastback.hud.restore-percent", 0));
        final ProgressMonitor pm = new JGitIncrementalProgressMonitor(new JGitRestoreProgressMonitor(ulog), 100);
        try (Git git = Git.cloneRepository().setProgressMonitor(pm).setDirectory(restoreTargetDir.toFile()).
                setBranchesToClone(List.of("refs/heads/" + branchName)).setBranch(branchName).setURI(repoUri).call()) {
        }
        FileUtils.rmdir(restoreTargetDir.resolve(".git"));
    }

    /**
     * @param allRestoresDir - general location for restorations to go.  e.g., the 'saves' dir by default if client
     * @param worldName      - name of the world
     * @param snapshotName   - name of the snapshot being restored
     * @return The absolute path to the directory where the snapshot should be restored
     */
    static Path getTargetDir(Path allRestoresDir, String worldName, String snapshotName) {
        worldName = worldName.replaceAll("[^\\p{L}\\p{N}]+", ""); // strip out all non-word characters for safety
        Path base = allRestoresDir.resolve(worldName + "-" + snapshotName);
        Path candidate = base;
        int i = 0;
        while (candidate.toFile().exists()) {
            i++;
            candidate = Path.of(base + "_" + i);
            if (i > 1000) {
                throw new IllegalStateException("wat i = " + i);
            }
        }
        return candidate;
    }

    private static class JGitRestoreProgressMonitor extends JGitPercentageProgressMonitor {

        private final UserLogger ulog;

        public JGitRestoreProgressMonitor(UserLogger ulog) {
            this.ulog = requireNonNull(ulog);
        }

        @Override
        public void progressStart(String task) {
        }
        //remote: Finding sources
        //Receiving objects
        //Updating references
        //Checking out files   %

        @Override
        public void progressUpdate(String task, int percentage) {
            final String message = task + " " + percentage + "%";
            syslog().debug(message);
            this.ulog.update(styledRaw(message, UserMessageStyle.JGIT));
        }

        @Override
        public void progressDone(String task) {
        }

        @Override
        public void showDuration(boolean enabled) {
        }

    }
}
