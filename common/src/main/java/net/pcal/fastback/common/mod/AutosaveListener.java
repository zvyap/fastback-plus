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

import net.pcal.fastback.common.config.ActionConfig;
import net.pcal.fastback.common.config.ActionRule;
import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.repo.Repo;
import net.pcal.fastback.common.repo.RepoFactory;
import net.pcal.fastback.common.utils.Executor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.config.FastbackConfigKey.IS_BACKUP_ENABLED;
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

    private final ActionSchedule schedule = new ActionSchedule();
    private final LongSupplier clock;
    private final Supplier<LocalDateTime> wallClock;

    AutosaveListener() {
        this(System::nanoTime, LocalDateTime::now);
    }

    AutosaveListener(LongSupplier clock) {
        this(clock, LocalDateTime::now);
    }

    AutosaveListener(LongSupplier clock, Supplier<LocalDateTime> wallClock) {
        this.clock = requireNonNull(clock);
        this.wallClock = requireNonNull(wallClock);
        reset(false);
    }

    void reset(boolean empty) {
        this.schedule.reset(empty, this.clock.getAsLong(), this.wallClock.get());
    }

    void onServerPlayersChanged(boolean empty) {
        this.schedule.playersChanged(empty, this.clock.getAsLong());
    }

    void onManualBackupCompleted() {
        this.schedule.manualBackupCompleted(this.clock.getAsLong());
    }

    void onActionsChanged(List<ActionRule> rules) {
        this.schedule.actionsChanged(rules, this.clock.getAsLong(), this.wallClock.get());
    }

    void initializeActions() {
        final Path world = mod().getWorldDirectory();
        if (!RepoFactory.rf().isGitRepo(world)) return;
        try (final Repo repo = RepoFactory.rf().load(world)) {
            this.schedule.sync(ActionConfig.loadAndMigrate(repo.getConfig()), this.clock.getAsLong(), this.wallClock.get());
        } catch (Exception e) {
            syslog().error("Could not migrate automatic backup actions. Existing configuration was retained.", e);
        }
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
        final List<ActionRule> rules = ActionConfig.loadAndMigrate(config);
        if (!config.getBoolean(IS_BACKUP_ENABLED)) return;
        final long now = this.clock.getAsLong();
        final LocalDateTime wallTime = this.wallClock.get();
        this.schedule.sync(rules, now, wallTime);
        final long occupancy = this.schedule.occupancy();
        for (final ActionRule rule : rules) {
            // Occupancy can change while the worker reads the configuration or runs an earlier action.
            if (!this.schedule.isDue(rule, occupancy, this.clock.getAsLong(), this.wallClock.get())) continue;
            Executor.checkCancelled();
            syslog().info("Starting automatic action " + rule.eventArgument() + " " + rule.action().getArgumentName());
            try {
                final boolean success = rule.action().getTask(repo, ulog).call();
                Executor.checkCancelled();
                this.schedule.completed(rule, occupancy, this.clock.getAsLong(), this.wallClock.get(), success);
            } catch (Exception e) {
                Executor.checkCancelled();
                syslog().error("Automatic action failed: " + rule.eventArgument() + " " + rule.action().getArgumentName(), e);
            }
        }
    }

}
