package net.pcal.fastback.common.commands;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotId;
import net.pcal.fastback.common.repo.SnapshotMetadata;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static net.minecraft.ChatFormatting.AQUA;
import static net.minecraft.ChatFormatting.DARK_GRAY;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.ITALIC;
import static net.minecraft.ChatFormatting.YELLOW;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;

/** Shared snapshot fields for chat details, hovers and command completion. */
final class SnapshotPresentation {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    static String formatDate(SnapshotId id) {
        return DATE_FORMAT.format(id.getDate().toInstant().atZone(ZoneId.systemDefault()));
    }

    static Component details(SnapshotDetails details, boolean remote) {
        return tooltip(details, remote, false);
    }

    static Component tooltip(SnapshotDetails details, boolean remote, boolean compact) {
        final MutableComponent text = Component.empty();
        // Suggestion tooltips are drawn on one line; LF renders as a missing glyph there.
        final Component separator = Component.literal(compact ? " | " : "\n  ").withStyle(DARK_GRAY);
        if (!compact) {
            text.append("  ").append(field("fastback.chat.view-id", Component.literal(details.id().getShortName()).withStyle(YELLOW)))
                    .append(separator);
        }
        return text.append(field("fastback.chat.view-date", Component.literal(formatDate(details.id())).withStyle(AQUA)))
                .append(separator)
                .append(field("fastback.chat.view-created-by", creatorText(details)))
                .append(separator)
                .append(field("fastback.chat.view-remark", remarkText(details, remote)));
    }

    static Component creatorText(SnapshotDetails details) {
        final SnapshotMetadata metadata = details.metadata();
        return metadata == null ? messageToText(localized("fastback.values.unknown")).copy().withStyle(GRAY)
                : metadata.creator() == null ? messageToText(localized("fastback.values.automatic")).copy().withStyle(GREEN)
                : Component.literal(singleLine(metadata.creator())).withStyle(AQUA);
    }

    static Component remarkText(SnapshotDetails details, boolean remote) {
        final SnapshotMetadata metadata = details.metadata();
        final String remark = metadata == null || metadata.remark() == null ? "" : singleLine(metadata.remark());
        return remark.isEmpty() ? Component.literal("-").withStyle(DARK_GRAY)
                : Component.literal(remark).withStyle(GRAY, ITALIC);
    }

    private static Component field(String key, Component value) {
        return messageToText(localized(key, value)).copy().withStyle(GRAY);
    }

    private static String singleLine(String value) {
        final StringBuilder text = new StringBuilder();
        value.codePoints().forEach(codePoint -> text.appendCodePoint(Character.isISOControl(codePoint)
                || codePoint == 0x2028 || codePoint == 0x2029 ? ' ' : codePoint));
        return text.toString().strip();
    }

    private SnapshotPresentation() {}
}
