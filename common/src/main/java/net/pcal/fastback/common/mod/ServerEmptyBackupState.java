package net.pcal.fastback.common.mod;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Tracks successful automatic backups within one continuous empty-server period. */
final class ServerEmptyBackupState {

    private boolean empty;
    private long generation;
    private long lastBackupTime;
    private long backups;
    private String stopReason;

    synchronized void reset(boolean empty, long now) {
        this.empty = empty;
        this.generation++;
        this.lastBackupTime = now;
        this.backups = 0;
        this.stopReason = null;
    }

    synchronized void update(boolean empty, long now) {
        if (this.empty != empty) reset(empty, now);
    }

    synchronized long period() {
        return this.empty ? this.generation : -1;
    }

    synchronized void refreshWait(long now) {
        this.lastBackupTime = now;
    }

    synchronized boolean isDue(long period, long now, int waitMinutes, int max) {
        return this.empty && this.generation == period && waitMinutes >= 0 && max >= 0 &&
                (max == 0 || this.backups < max) &&
                now - this.lastBackupTime >= TimeUnit.MINUTES.toNanos(waitMinutes);
    }

    synchronized boolean isLimitReached(long period, int max) {
        return this.empty && this.generation == period && max > 0 && this.backups >= max;
    }

    /** Returns whether the stop reason changed; null clears it when backups can resume. */
    synchronized boolean updateStopReason(long period, String reason) {
        if (!this.empty || this.generation != period || Objects.equals(this.stopReason, reason)) return false;
        this.stopReason = reason;
        return true;
    }

    synchronized void completed(long period, long now, boolean success) {
        if (success && this.empty && this.generation == period) {
            this.lastBackupTime = now;
            this.backups++;
        }
    }
}
