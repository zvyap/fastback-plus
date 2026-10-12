package net.pcal.fastback.common.mod;

import net.pcal.fastback.common.config.ActionRule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Session counters and clocks; automatic actions are checked only after a safe vanilla autosave. */
final class ActionSchedule {

    private final Map<ActionRule, State> states = new HashMap<>();
    private boolean empty;
    private long occupancy;
    private long intervalStart;
    private LocalDateTime startedAt;
    private boolean initialized;

    private static final class State {
        long lastInterval;
        long successes;
        long completedEmptyPeriod = -1;
        LocalDateTime nextScheduled;

        State(ActionRule rule, long now, LocalDateTime wallTime) {
            this.lastInterval = now;
            if (rule.event() == ActionRule.Event.SCHEDULED) {
                this.nextScheduled = wallTime.toLocalDate().atTime(rule.scheduledTime());
                if (this.nextScheduled.isBefore(wallTime)) this.nextScheduled = this.nextScheduled.plusDays(1);
            }
        }
    }

    synchronized void reset(boolean empty, long now, LocalDateTime wallTime) {
        this.states.clear();
        this.empty = empty;
        this.occupancy++;
        this.intervalStart = now;
        this.startedAt = wallTime;
        this.initialized = false;
    }

    synchronized void sync(List<ActionRule> rules, long now, LocalDateTime wallTime) {
        this.states.keySet().retainAll(rules);
        for (final ActionRule rule : rules) {
            this.states.computeIfAbsent(rule, key -> new State(key,
                    this.initialized ? now : this.intervalStart,
                    this.initialized ? wallTime : this.startedAt));
        }
        this.initialized = true;
    }

    synchronized void actionsChanged(List<ActionRule> rules, long now, LocalDateTime wallTime) {
        if (!this.initialized) {
            this.intervalStart = now;
            this.startedAt = wallTime;
        }
        sync(rules, now, wallTime);
    }

    synchronized void playersChanged(boolean empty, long now) {
        if (this.empty == empty) return;
        this.empty = empty;
        this.occupancy++;
        // Start a fresh interval after players return, rather than immediately running an overdue backup.
        if (!empty) manualBackupCompleted(now);
    }

    synchronized void manualBackupCompleted(long now) {
        this.intervalStart = now;
        for (final State state : this.states.values()) state.lastInterval = now;
    }

    synchronized long occupancy() {
        return this.occupancy;
    }

    synchronized boolean isDue(ActionRule rule, long occupancy, long now, LocalDateTime wallTime) {
        final State state = this.states.get(rule);
        if (state == null || occupancy != this.occupancy ||
                (rule.max() > 0 && state.successes >= rule.max())) return false;
        return switch (rule.event()) {
            case SHUTDOWN -> false;
            case EMPTY -> this.empty && state.completedEmptyPeriod != this.occupancy;
            case INTERVAL -> !this.empty && Duration.ofNanos(now - state.lastInterval).compareTo(rule.interval()) >= 0;
            case SCHEDULED -> !wallTime.isBefore(state.nextScheduled);
        };
    }

    synchronized void completed(ActionRule rule, long occupancy, long now, LocalDateTime wallTime, boolean success) {
        final State state = this.states.get(rule);
        if (state == null) return;
        if (rule.event() == ActionRule.Event.INTERVAL) state.lastInterval = now;
        if (!success) return;
        state.successes++;
        if (rule.event() == ActionRule.Event.EMPTY && this.empty && this.occupancy == occupancy) {
            state.completedEmptyPeriod = occupancy;
        } else if (rule.event() == ActionRule.Event.SCHEDULED) {
            final LocalDateTime nextOccurrence = state.nextScheduled.plusDays(1);
            LocalDateTime afterCompletion = wallTime.toLocalDate().atTime(rule.scheduledTime());
            if (!afterCompletion.isAfter(wallTime)) afterCompletion = afterCompletion.plusDays(1);
            state.nextScheduled = nextOccurrence.isAfter(afterCompletion) ? nextOccurrence : afterCompletion;
        }
    }
}
