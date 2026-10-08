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

package net.pcal.fastback.common.mod;

import net.pcal.fastback.common.commands.SchedulableAction;
import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.repo.Repo;
import net.pcal.fastback.common.repo.RepoFactory;
import net.pcal.fastback.common.utils.Executor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.commands.SchedulableAction.NONE;
import static net.pcal.fastback.common.commands.SchedulableAction.forConfigValue;
import static net.pcal.fastback.common.config.FastbackConfigKey.AUTOBACK_ACTION;
import static net.pcal.fastback.common.config.FastbackConfigKey.AUTOBACK_WAIT_MINUTES;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_BACKUP_ENABLED;
import static net.pcal.fastback.common.config.FastbackConfigKey.SERVER_EMPTY_ACTION;
import static net.pcal.fastback.common.config.FastbackConfigKey.SERVER_EMPTY_MAX;
import static net.pcal.fastback.common.config.FastbackConfigKey.SERVER_EMPTY_WAIT_MINUTES;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.utils.Executor.executor;

/**
 * Responds to vanilla autosaves and follows them with an automatic backup (autoback).
 *
 * @author pcal
 * @since 0.2.0
 */
class AutosaveListener implements Runnable {

    private final ServerEmptyBackupState serverEmpty = new ServerEmptyBackupState();
    private final LongSupplier clock;
    private volatile long lastBackupTime;

    AutosaveListener() {
        this(System::nanoTime);
    }

    AutosaveListener(LongSupplier clock) {
        this.clock = requireNonNull(clock);
        reset(false);
    }

    void reset(boolean empty) {
        this.lastBackupTime = this.clock.getAsLong();
        this.serverEmpty.reset(empty, this.lastBackupTime);
    }

    void onServerPlayersChanged(boolean empty) {
        this.serverEmpty.update(empty, this.clock.getAsLong());
    }

    void onManualBackupCompleted() {
        final long now = this.clock.getAsLong();
        this.lastBackupTime = now;
        this.serverEmpty.refreshWait(now);
    }

    @Override
    public void run() {
        try (final UserLogger ulog = UserLogger.forAutosave()) {
            executor().execute(Executor.ExecutionLock.WRITE, ulog, () -> {
                try {
                    final RepoFactory rf = RepoFactory.rf();
                    final Path worldSaveDir = mod().getWorldDirectory();
                    if (!rf.isGitRepo(worldSaveDir)) return;
                    try (final Repo repo = rf.load(worldSaveDir)) {
                        backupIfDue(repo, ulog);
                    }
                } catch (Exception e) {
                    Executor.checkCancelled();
                    syslog().error("auto-backup failed.", e);
                }
            });
        }
    }

    void backupIfDue(Repo repo, UserLogger ulog) throws Exception {
        final GitConfig config = repo.getConfig();
        if (!config.getBoolean(IS_BACKUP_ENABLED)) return;
        final long emptyPeriod = this.serverEmpty.period();
        final boolean emptyPolicy = emptyPeriod >= 0 && (config.isSet(SERVER_EMPTY_ACTION) ||
                config.isSet(SERVER_EMPTY_WAIT_MINUTES) || config.isSet(SERVER_EMPTY_MAX));
        final SchedulableAction action = forConfigValue(config,
                emptyPolicy && config.isSet(SERVER_EMPTY_ACTION) ? SERVER_EMPTY_ACTION : AUTOBACK_ACTION);
        if (action == null || action == NONE) return;
        final int waitMinutes = config.getInt(emptyPolicy && config.isSet(SERVER_EMPTY_WAIT_MINUTES)
                ? SERVER_EMPTY_WAIT_MINUTES : AUTOBACK_WAIT_MINUTES);
        final long now = this.clock.getAsLong();
        if (emptyPolicy) {
            if (!this.serverEmpty.isDue(emptyPeriod, now, waitMinutes, config.getInt(SERVER_EMPTY_MAX))) return;
        } else {
            final Duration timeRemaining = Duration.ofMinutes(waitMinutes)
                    .minus(Duration.ofNanos(now - this.lastBackupTime));
            if (!timeRemaining.isZero() && !timeRemaining.isNegative()) {
                syslog().debug("Skipping auto-backup until at least " +
                        (timeRemaining.toSeconds() / 60) + " more minutes have elapsed.");
                return;
            }
        }
        // A player event may have changed the policy while this worker was checking configuration.
        if (this.serverEmpty.period() != emptyPeriod) return;
        Executor.checkCancelled();
        syslog().info("Starting auto-backup" + (emptyPolicy ? " for empty server" : ""));
        final boolean success = action.getTask(repo, ulog).call();
        Executor.checkCancelled();
        final long completed = this.clock.getAsLong();
        if (success || !emptyPolicy) this.lastBackupTime = completed;
        this.serverEmpty.completed(emptyPeriod, completed, success);
    }

}
