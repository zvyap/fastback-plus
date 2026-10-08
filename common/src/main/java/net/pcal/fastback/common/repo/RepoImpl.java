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

import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage;
import net.pcal.fastback.common.repo.SnapshotIdUtils.SnapshotIdCodec;
import net.pcal.fastback.common.repo.WorldIdUtils.WorldIdInfo;
import net.pcal.fastback.common.utils.ProcessException;
import net.pcal.fastback.common.utils.Executor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.config.FastbackConfigKey.BROADCAST_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.BROADCAST_MESSAGE;
import static net.pcal.fastback.common.config.FastbackConfigKey.BROADCAST_DONE_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.BROADCAST_DONE_MESSAGE;
import static net.minecraft.ChatFormatting.AQUA;
import static net.minecraft.ChatFormatting.GOLD;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_LOCK_CLEANUP_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_NATIVE_GIT_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.REMOTE_NAME;
import static net.pcal.fastback.common.config.OtherConfigKey.REMOTE_PUSH_URL;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.BROADCAST;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.WARNING;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.logging.UserMessage.styledRaw;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.mod.UserMessageUtil.configuredMessage;
import static net.pcal.fastback.common.repo.PushUtils.jgit_lsRemote;
import static net.pcal.fastback.common.repo.PushUtils.native_lsRemote;
import static net.pcal.fastback.common.utils.EnvironmentUtils.isNativeOk;
import static org.eclipse.jgit.util.FileUtils.RETRY;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;

/**
 * @author pcal
 * @since 0.13.0
 */
class RepoImpl implements Repo {

    // ======================================================================
    // Constants

    static final String FASTBACK_DIR = ".fastback";
    private static final DateTimeFormatter SNAPSHOT_NAME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss").withResolverStyle(ResolverStyle.STRICT);

    // ======================================================================
    // Fields

    private final Git jgit;
    private GitConfig config;
    private WorldIdInfo worldIdInfo;

    // ======================================================================
    // Constructors

    RepoImpl(final Git jgit) {
        this.jgit = requireNonNull(jgit);
    }

    // ======================================================================
    // 'do' methods - implement higher-level command-oriented logic.

    @Override
    public boolean doCommitAndPush(final UserLogger ulog, final SnapshotMetadata metadata) {
        requireNonNull(metadata);
        if (!isNativeOk(this.getConfig(), ulog, false)) return false;
        checkIndexLock(ulog);
        broadcastBackupNotice();
        final long start = System.nanoTime();
        final SnapshotId newSid;
        try {
            newSid = CommitUtils.doCommitSnapshot(this, ulog, metadata);
        } catch (IOException | GitAPIException | ProcessException e) {
            Executor.checkCancelled();
            syslog().error(e);
            ulog.message(styledLocalized("fastback.chat.commit-failed", ERROR));
            return false;
        }
        try {
            if (!getConfig().isSet(REMOTE_PUSH_URL)) {
                ulog.message(styledLocalized("fastback.chat.remote-no-url", ERROR));
                return false;
            }
            PushUtils.doPush(newSid, this, ulog);
        } catch (IOException | ProcessException e) {
            Executor.checkCancelled();
            ulog.message(styledLocalized("fastback.chat.push-failed", ERROR));
            syslog().error(e);
            return false;
        } finally {
            invalidateSnapshots();
        }
        Executor.checkCancelled();
        ulog.message(localized("fastback.chat.backup-complete-elapsed", getDuration(start)));
        broadcastBackupDone(newSid, metadata, elapsedMillis(start));
        return true;
    }

    @Override
    public boolean doCommitSnapshot(final UserLogger ulog, final SnapshotMetadata metadata) {
        requireNonNull(metadata);
        if (!isNativeOk(this.getConfig(), ulog, false)) return false;
        checkIndexLock(ulog);
        broadcastBackupNotice();
        final long start = System.nanoTime();
        final SnapshotId newSid;
        try {
            newSid = CommitUtils.doCommitSnapshot(this, ulog, metadata);
        } catch (IOException | ProcessException | GitAPIException e) {
            Executor.checkCancelled();
            ulog.message(styledLocalized("fastback.chat.commit-failed", ERROR));
            syslog().error(e);
            return false;
        }
        Executor.checkCancelled();
        ulog.message(localized("fastback.chat.backup-complete-elapsed", getDuration(start)));
        broadcastBackupDone(newSid, metadata, elapsedMillis(start));
        return true;
    }

    @Override
    public void backupBeforeLoad(UserLogger ulog) throws Exception {
        if (!isNativeOk(this.getConfig(), ulog, false)) throw new IOException("Backup tools are unavailable");
        checkIndexLock(ulog);
        CommitUtils.doCommitSnapshot(this, ulog, SnapshotMetadata.AUTOMATIC);
    }

    @Override
    public void doPushSnapshot(SnapshotId sid, final UserLogger ulog) {
        if (!this.getConfig().isSet(REMOTE_PUSH_URL)) {
            ulog.message(styledLocalized("fastback.chat.remote-no-url", ERROR));
            return;
        }
        if (!isNativeOk(this.getConfig(), ulog, false)) return;
        final long start = System.nanoTime();
        try {
            PushUtils.doPush(sid, this, ulog);
        } catch (IOException | ProcessException e) {
            Executor.checkCancelled();
            ulog.message(styledLocalized("fastback.chat.commit-failed", ERROR));
            syslog().error(e);
            return;
        } finally {
            invalidateSnapshots();
        }
        Executor.checkCancelled();
        ulog.message(UserMessage.localized("fastback.chat.push-done-elapsed", sid.getShortName(), getDuration(start)));
    }


    @Override
    public Collection<SnapshotId> doLocalPrune(final UserLogger ulog) throws IOException {
        try {
            return PruneUtils.doLocalPrune(this, ulog);
        } finally {
            invalidateSnapshots();
        }
    }

    @Override
    public Collection<SnapshotId> doRemotePrune(final UserLogger ulog) throws IOException {
        try {
            return PruneUtils.doRemotePrune(this, ulog);
        } finally {
            invalidateSnapshots();
        }
    }

    @Override
    public void doGc(final UserLogger ulog) {
        if (!isNativeOk(this.getConfig(), ulog, false)) return;
        try {
            ReclamationUtils.doReclamation(this, ulog);
        } catch (ProcessException | GitAPIException e) {
            Executor.checkCancelled();
            ulog.message(styledLocalized("fastback.chat.gc-failed", ERROR));
            syslog().error(e);
        }
    }

    @Override
    public void doRestoreLocalSnapshot(String snapshotName, UserLogger ulog) {
        RestoreUtils.doRestoreLocalSnapshot(snapshotName, this, ulog);
    }

    @Override
    public void doRestoreRemoteSnapshot(String snapshotName, UserLogger ulog) {
        try {
            RestoreUtils.doRestoreRemoteSnapshot(snapshotName, this, ulog);
        } finally {
            invalidateSnapshots();
        }
    }

    @Override
    public void doLoadSnapshot(String snapshotName, boolean remote, UserLogger ulog) {
        try {
            RestoreUtils.doLoadSnapshot(snapshotName, remote, this, ulog);
        } finally {
            invalidateSnapshots();
        }
    }

    // ======================================================================
    // Other repo implementation

    @Override
    public WorldId getWorldId() throws IOException {
        return WorldIdUtils.getWorldIdInfo(this.getWorkTree().toPath()).wid();
    }

    @Override
    public Set<SnapshotId> getLocalSnapshots() throws IOException {
        final JGitSupplier<Collection<String>> refProvider = () -> {
            try {
                return jgit.branchList().call().stream().map(Ref::getName).toList();
            } catch (GitAPIException e) {
                throw new IOException(e);
            }
        };
        try {
            return BranchUtils.listSnapshots(this, refProvider);
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Set<SnapshotId> getRemoteSnapshots() throws IOException {
        final GitConfig conf = GitConfig.load(jgit);
        final String remoteName = conf.getString(REMOTE_NAME);
        final JGitSupplier<Collection<String>> refProvider = () -> {
            try {
                if (conf.getBoolean(IS_NATIVE_GIT_ENABLED)) {
                    return native_lsRemote(this.getWorkTree().toPath(), remoteName);
                } else {
                    return jgit_lsRemote(this.jgit, remoteName);
                }
            } catch (GitAPIException | ProcessException e) {
                throw new IOException(e);
            }
        };
        try {
            return BranchUtils.listSnapshots(this, refProvider);
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<SnapshotDetails> getLocalSnapshotDetails() throws IOException {
        Executor.checkCancelled();
        final List<SnapshotId> snapshots = getLocalSnapshots().stream().sorted(Comparator.reverseOrder()).toList();
        return getSnapshotDetails(snapshots);
    }

    @Override
    public List<SnapshotDetails> getSnapshotDetails(Collection<SnapshotId> snapshots) throws IOException {
        Executor.checkCancelled();
        final List<SnapshotDetails> details = new ArrayList<>(snapshots.size());
        try (final RevWalk walk = new RevWalk(jgit.getRepository())) {
            for (final SnapshotId snapshot : snapshots) {
                Executor.checkCancelled();
                final SnapshotDetails detail = readSnapshotDetails(snapshot, walk);
                details.add(detail == null ? new SnapshotDetails(snapshot, null) : detail);
            }
        }
        return List.copyOf(details);
    }

    @Override
    public SnapshotDetails getSnapshotDetails(String snapshotName) throws IOException {
        if (snapshotName == null) return null;
        try {
            LocalDateTime.parse(snapshotName, SNAPSHOT_NAME_FORMAT);
            final SnapshotId snapshot = createSnapshotId(snapshotName);
            try (final RevWalk walk = new RevWalk(jgit.getRepository())) {
                return readSnapshotDetails(snapshot, walk);
            }
        } catch (DateTimeParseException | ParseException invalidName) {
            return null;
        }
    }

    private SnapshotDetails readSnapshotDetails(SnapshotId snapshot, RevWalk walk) throws IOException {
        final Ref ref = jgit.getRepository().exactRef("refs/heads/" + snapshot.getBranchName());
        if (ref == null || ref.getObjectId() == null) return null;
        final String message = walk.parseCommit(ref.getObjectId()).getFullMessage();
        return new SnapshotDetails(snapshot, SnapshotMetadata.fromCommitMessage(message));
    }

    @Override
    public GitConfig getConfig() {
        if (this.config == null) {
            this.config = GitConfig.load(this.jgit);
        }
        return this.config;
    }

    @Override
    public File getDirectory() throws NoWorkTreeException {
        return this.jgit.getRepository().getDirectory();
    }

    @Override
    public File getWorkTree() throws NoWorkTreeException {
        return this.jgit.getRepository().getWorkTree();
    }

    @Override
    public void deleteRemoteBranch(String remoteBranchName) throws IOException {
        try {
            PruneUtils.deleteRemoteBranch(this, remoteBranchName);
        } finally {
            invalidateSnapshots();
        }
    }

    @Override
    public void deleteLocalBranches(final List<String> branchesToDelete) throws IOException {
        try {
            PruneUtils.deleteLocalBranches(this, branchesToDelete);
        } finally {
            invalidateSnapshots();
        }
    }

    @Override
    public void close() {
        this.getJGit().close();
    }

    @Override
    public SnapshotId createSnapshotId(String shortName) throws IOException, ParseException {
        return getWorldIdInfo().sidCodec().create(this.getWorldId(), shortName);
    }

    // ======================================================================
    // Package-private

    SnapshotIdCodec getSidCodec() throws IOException {
        return this.getWorldIdInfo().sidCodec();
    }

    Git getJGit() {
        return this.jgit;
    }

    Path getDotFasbackDir() {
        return this.getWorkTree().toPath().resolve(FASTBACK_DIR);
    }

    // ======================================================================
    // Private

    private void invalidateSnapshots() {
        SnapshotCache.invalidate(getWorkTree().toPath());
    }

    private WorldIdInfo getWorldIdInfo() throws IOException {
        if (this.worldIdInfo == null) {
            this.worldIdInfo = WorldIdUtils.getWorldIdInfo(this.getWorkTree().toPath());
        }
        return this.worldIdInfo;
    }

    private void checkIndexLock(UserLogger ulog) {
        final File lockFile = this.getWorkTree().toPath().resolve(".git/index.lock").toFile();
        if (lockFile.exists()) {
            ulog.message(styledLocalized("fastback.chat.lockfile-exists", WARNING, lockFile.getAbsolutePath()));
            if (getConfig().getBoolean(IS_LOCK_CLEANUP_ENABLED)) {
                ulog.message(styledLocalized("fastback.chat.lockfile-cleanup-enabled", WARNING, "lock-cleanup-enabled = true"));
                try {
                    FileUtils.delete(lockFile, RETRY);
                } catch (IOException e) {
                    syslog().debug(e); // we kind of don't care
                }
                if (lockFile.exists()) {
                    ulog.message(styledRaw("Cleanup failed.  Your backup will probably not succeed.", ERROR));
                } else {
                    ulog.message(styledRaw("Cleanup succeeded, proceeding with backup.  But if you see this message again, you should check your system to see if some other git process is accessing your backup.", WARNING));
                }
            } else {
                ulog.message(styledRaw("Please check to see if other processes are using this git repo.  If you're sure they aren't, you can enable automatic index.lock cleanup by typing '/set lock-cleanup enabled'", WARNING));
                ulog.message(styledRaw("Proceeding with backup but it will probably not succeed.", WARNING));
            }
        }
    }

    private static String getDuration(long since) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis(since));
        if (seconds < 60) {
            return String.format("%ds", seconds == 0 ? 1 : seconds);
        } else {
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        }
    }

    private void broadcastBackupNotice() {
        if (!getConfig().getBoolean(BROADCAST_ENABLED)) return;
        final UserMessage m;
        final String configuredMessage = getConfig().getString(BROADCAST_MESSAGE);
        if (configuredMessage != null) {
            m = configuredMessage(configuredMessage, Map.of(), BROADCAST);
        } else {
            m = styledLocalized("fastback.broadcast.message", BROADCAST);
        }
        mod().sendBroadcast(m);
    }

    private static long elapsedMillis(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    /** Called by the backup worker after saving (and, for full backups, pushing) succeeds. */
    private void broadcastBackupDone(SnapshotId snapshot, SnapshotMetadata metadata, long elapsedMillis) {
        if (!getConfig().getBoolean(BROADCAST_DONE_ENABLED)) return;
        final String elapsed = BackupCompletion.elapsedText(elapsedMillis);
        final String template = getConfig().getString(BROADCAST_DONE_MESSAGE);
        String snapshotSize = "-";
        String totalSize = "-";
        if (template == null || template.contains("{snapshot_size}")) {
            try {
                snapshotSize = byteCountToDisplaySize(BackupCompletion.snapshotSize(jgit.getRepository(), snapshot));
            } catch (Exception unavailable) {
                Executor.checkCancelled();
                syslog().warn("Unable to calculate completed snapshot size.");
                syslog().debug(unavailable);
            }
        }
        if (template == null || template.contains("{total_size}")) {
            try {
                totalSize = byteCountToDisplaySize(sizeOfDirectory(getDirectory()));
            } catch (Exception unavailable) {
                Executor.checkCancelled();
                syslog().warn("Unable to calculate total backup size.");
                syslog().debug(unavailable);
            }
        }
        Executor.checkCancelled();
        final UserMessage message;
        if (template == null) {
            message = styledLocalized("fastback.broadcast.completed-elapsed", BROADCAST,
                    Component.literal(elapsed).withStyle(AQUA),
                    Component.literal(snapshotSize).withStyle(GOLD),
                    Component.literal(totalSize).withStyle(GOLD));
        } else {
            message = configuredMessage(template, Map.of(
                    "snapshot", snapshot.getShortName(),
                    "snapshot_size", snapshotSize,
                    "total_size", totalSize,
                    "elapsed", elapsed,
                    "remark", metadata.remark() == null || metadata.remark().isBlank() ? "-" : metadata.remark(),
                    "creator", metadata.creator() == null ? "automatic" : metadata.creator()), BROADCAST);
        }
        mod().sendBroadcast(message);
    }
}
