package net.pcal.fastback.common.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.pcal.fastback.common.commands.SchedulableAction;
import net.pcal.fastback.common.config.ActionRule.Event;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.pcal.fastback.common.config.FastbackConfigKey.*;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;

/** Persistence and one-time migration of automatic actions in the world's Git config. */
public final class ActionConfig {

    private static final List<FastbackConfigKey> LEGACY_KEYS = List.of(AUTOBACK_ACTION, AUTOBACK_WAIT_MINUTES,
            SERVER_EMPTY_ACTION, SERVER_EMPTY_WAIT_MINUTES, SERVER_EMPTY_MAX, SHUTDOWN_ACTION);

    private ActionConfig() {}

    public static List<ActionRule> defaults() {
        return List.of(new ActionRule(Event.SHUTDOWN, SchedulableAction.FULL, null, 0),
                new ActionRule(Event.INTERVAL, SchedulableAction.LOCAL, "30m", 0));
    }

    /** Read-only: configuration writes are performed only by migration or action commands. */
    public static List<ActionRule> load(GitConfig config) {
        if (!config.isSet(ACTIONS)) return legacyActions(config);
        try {
            final List<ActionRule> actions = new ArrayList<>();
            for (final JsonElement element : JsonParser.parseString(config.getString(ACTIONS)).getAsJsonArray()) {
                final JsonObject json = element.getAsJsonObject();
                actions.add(new ActionRule(Event.valueOf(json.get("event").getAsString()),
                        SchedulableAction.forConfigValue(json.get("action").getAsString()),
                        json.has("value") ? json.get("value").getAsString() : null,
                        json.has("max") ? Integer.parseInt(json.get("max").getAsString()) : 0));
            }
            validate(actions);
            return List.copyOf(actions);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid FastBack actions configuration: " + e.getMessage(), e);
        }
    }

    public static void migrate(GitConfig config) throws IOException {
        if (!config.isSet(ACTIONS)) save(config, legacyActions(config));
    }

    /** A read-only config must not disable existing backups merely because migration cannot be saved. */
    public static List<ActionRule> loadAndMigrate(GitConfig config) {
        try {
            migrate(config);
        } catch (IOException e) {
            syslog().error("Could not save automatic action migration; retaining the existing backup configuration.", e);
        }
        return load(config);
    }

    public static void save(GitConfig config, List<ActionRule> actions) throws IOException {
        validate(actions);
        final JsonArray json = new JsonArray();
        for (final ActionRule rule : actions) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("event", rule.event().name());
            entry.addProperty("action", rule.action().getConfigValue());
            if (rule.value() != null) entry.addProperty("value", rule.value());
            entry.addProperty("max", rule.max());
            json.add(entry);
        }
        final Map<FastbackConfigKey, String> previous = new HashMap<>();
        previous.put(ACTIONS, config.isSet(ACTIONS) ? config.getString(ACTIONS) : null);
        for (final FastbackConfigKey key : LEGACY_KEYS) previous.put(key, config.isSet(key) ? config.getString(key) : null);
        final GitConfig.Updater updater = config.updater().set(ACTIONS, json.toString());
        for (final FastbackConfigKey key : LEGACY_KEYS) updater.unset(key);
        try {
            updater.save();
        } catch (IOException e) {
            // The updater changes the in-memory config before saving; keep it usable if the disk write fails.
            for (final Map.Entry<FastbackConfigKey, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) updater.unset(entry.getKey());
                else updater.set(entry.getKey(), entry.getValue());
            }
            throw e;
        }
    }

    private static List<ActionRule> legacyActions(GitConfig config) {
        final int emptyMax = config.getInt(SERVER_EMPTY_MAX);
        if (emptyMax < 0) throw new IllegalArgumentException("Legacy backup-server-empty-max cannot be negative.");
        final List<ActionRule> actions = new ArrayList<>();
        final SchedulableAction shutdown = legacyAction(config.getString(SHUTDOWN_ACTION));
        if (shutdown != null) actions.add(new ActionRule(Event.SHUTDOWN, shutdown, null, 0));
        final SchedulableAction interval = legacyAction(config.getString(AUTOBACK_ACTION));
        if (interval != null) {
            actions.add(new ActionRule(Event.INTERVAL, interval,
                    Math.max(0, config.getInt(AUTOBACK_WAIT_MINUTES)) + "m", 0));
        }
        final SchedulableAction empty = config.isSet(SERVER_EMPTY_ACTION)
                ? legacyAction(config.getString(SERVER_EMPTY_ACTION)) : interval;
        if (empty != null) {
            // EMPTY now runs once per continuous empty period; the old repeating wait has no equivalent.
            actions.add(new ActionRule(Event.EMPTY, empty, null, emptyMax));
        }
        return List.copyOf(actions);
    }

    private static SchedulableAction legacyAction(String value) {
        if (value == null || "none".equals(value)) return null;
        final SchedulableAction action = SchedulableAction.forConfigValue(value);
        if (action == null) throw new IllegalArgumentException("Unknown legacy backup action: " + value);
        return action;
    }

    private static void validate(List<ActionRule> actions) {
        final Set<String> identities = new HashSet<>();
        for (final ActionRule rule : actions) {
            if (!identities.add(rule.event().name() + ":" + rule.action().name())) {
                throw new IllegalArgumentException("An action already exists for " + rule.event() + " " + rule.action().getArgumentName() + ".");
            }
        }
    }
}
