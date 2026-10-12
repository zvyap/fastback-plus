package net.pcal.fastback.common.config;

import net.pcal.fastback.common.commands.SchedulableAction;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** One automatic action, identified by its event and action rather than its timing. */
public record ActionRule(Event event, SchedulableAction action, String value, int max) {

    public enum Event { SHUTDOWN, EMPTY, INTERVAL, SCHEDULED }

    private static final Pattern EVENT_ARGUMENT = Pattern.compile("([A-Z]+)(?:\\[([^\\[\\]]+)\\])?");
    private static final Pattern DURATION = Pattern.compile("([0-9]+)([smhd])");

    public ActionRule {
        requireNonNull(event, "Event is required");
        requireNonNull(action, "Action is required");
        if (max < 0) throw new IllegalArgumentException("MAX must be 0 (unlimited) or a positive integer.");
        if (event == Event.SHUTDOWN && max != 0) {
            throw new IllegalArgumentException("SHUTDOWN actions do not accept MAX.");
        }
        switch (event) {
            case INTERVAL -> {
                value = value == null ? "30m" : value;
                duration(value); // Zero is allowed here only for migrated legacy autosave intervals.
            }
            case SCHEDULED -> {
                value = value == null ? "00:00" : value;
                time(value);
            }
            case SHUTDOWN, EMPTY -> {
                if (value != null) throw new IllegalArgumentException(event + " does not accept a time value.");
            }
        }
    }

    public static ActionRule parse(String eventToken, SchedulableAction action, int max) {
        final Matcher matcher = EVENT_ARGUMENT.matcher(requireNonNull(eventToken));
        if (!matcher.matches()) throw new IllegalArgumentException("Use SHUTDOWN, EMPTY, INTERVAL[30m], or SCHEDULED[HH:MM].");
        final Event event;
        try {
            event = Event.valueOf(matcher.group(1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown event. Use SHUTDOWN, EMPTY, INTERVAL, or SCHEDULED.");
        }
        final ActionRule rule = new ActionRule(event, action, matcher.group(2), max);
        if (event == Event.INTERVAL && rule.interval().isZero()) {
            throw new IllegalArgumentException("INTERVAL must be positive, for example INTERVAL[30m].");
        }
        return rule;
    }

    public Duration interval() {
        if (event != Event.INTERVAL) throw new IllegalStateException("This is not an INTERVAL action.");
        return duration(value);
    }

    public LocalTime scheduledTime() {
        if (event != Event.SCHEDULED) throw new IllegalStateException("This is not a SCHEDULED action.");
        return time(value);
    }

    public String eventArgument() {
        return value == null ? event.name() : event.name() + "[" + value + "]";
    }

    private static Duration duration(String value) {
        final Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("INTERVAL needs a duration in s, m, h, or d, for example 30m.");
        try {
            final long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException();
            };
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("INTERVAL is too large.");
        }
    }

    private static LocalTime time(String value) {
        if (!value.matches("[0-9]{2}:[0-9]{2}")) {
            throw new IllegalArgumentException("SCHEDULED needs a 24-hour time in HH:MM, for example 00:00.");
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("SCHEDULED time must be between 00:00 and 23:59.");
        }
    }
}
