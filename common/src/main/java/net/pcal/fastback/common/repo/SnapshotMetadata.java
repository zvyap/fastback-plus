package net.pcal.fastback.common.repo;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Metadata recorded when a snapshot is created; a null creator means an automatic backup. */
public record SnapshotMetadata(String creator, String remark) {

    public static final int MAX_REMARK_LENGTH = 64;
    public static final SnapshotMetadata AUTOMATIC = new SnapshotMetadata(null, null);
    private static final String MESSAGE_SEPARATOR = "\n\nFastBack-Metadata-v1: ";

    public SnapshotMetadata {
        if (remark != null && remark.codePointCount(0, remark.length()) > MAX_REMARK_LENGTH) {
            throw new IllegalArgumentException("Backup remarks may contain at most " + MAX_REMARK_LENGTH + " characters");
        }
    }

    String toCommitMessage(String branchName) {
        final JsonObject json = new JsonObject();
        json.add("creator", creator == null ? JsonNull.INSTANCE : new JsonPrimitive(creator));
        json.add("remark", remark == null ? JsonNull.INSTANCE : new JsonPrimitive(remark));
        return branchName + MESSAGE_SEPARATOR + json;
    }

    /** Older snapshots or malformed metadata have an unknown creator, rather than an automatic one. */
    static SnapshotMetadata fromCommitMessage(String message) {
        final int separator = message.indexOf(MESSAGE_SEPARATOR);
        if (separator < 0) return null;
        try {
            final JsonObject json = JsonParser.parseString(message.substring(separator + MESSAGE_SEPARATOR.length()).trim()).getAsJsonObject();
            final String creator = readString(json, "creator");
            final String remark = readString(json, "remark");
            return new SnapshotMetadata(creator, remark);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String readString(JsonObject json, String name) {
        final JsonElement value = json.get(name);
        if (value == null) throw new IllegalArgumentException("Missing metadata field " + name);
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid metadata field " + name);
        }
        return value.getAsString();
    }
}
