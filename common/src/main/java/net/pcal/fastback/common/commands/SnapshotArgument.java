package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotMetadata;

import java.time.format.DateTimeFormatter;

import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;

/** Reusable snapshot argument using Minecraft's serializable string type. */
final class SnapshotArgument {

    // Minecraft sends unregistered providers as ask_server, so clients do not need a custom argument type.
    private static final SuggestionProvider<CommandSourceStack> LOCAL = new SnapshotNameSuggestions(false);
    private static final SuggestionProvider<CommandSourceStack> REMOTE = new SnapshotNameSuggestions(true);

    static RequiredArgumentBuilder<CommandSourceStack, String> local(String name) {
        return RequiredArgumentBuilder.<CommandSourceStack, String>argument(name, StringArgumentType.string()).suggests(LOCAL);
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> remote(String name) {
        return RequiredArgumentBuilder.<CommandSourceStack, String>argument(name, StringArgumentType.string()).suggests(REMOTE);
    }

    static Component detailsText(SnapshotDetails details) {
        return tooltip(details, false);
    }

    static Component tooltip(SnapshotDetails details, boolean remote) {
        return messageToText(localized("fastback.chat.view-date", DateTimeFormatter.ISO_INSTANT.format(details.id().getDate().toInstant()))).copy()
                .append("\n").append(messageToText(localized("fastback.chat.view-created-by", creatorText(details))))
                .append("\n").append(messageToText(localized("fastback.chat.view-remark", remarkText(details, remote))));
    }

    static Component creatorText(SnapshotDetails details) {
        final SnapshotMetadata metadata = details.metadata();
        return metadata == null ? messageToText(localized("fastback.values.unknown"))
                : metadata.creator() == null ? messageToText(localized("fastback.values.automatic"))
                : Component.literal(metadata.creator());
    }

    static Component remarkText(SnapshotDetails details, boolean remote) {
        final SnapshotMetadata metadata = details.metadata();
        return metadata == null && remote ? messageToText(localized("fastback.values.unknown"))
                : metadata == null || metadata.remark() == null || metadata.remark().isEmpty()
                ? messageToText(localized("fastback.values.no-remark")) : Component.literal(metadata.remark());
    }
}
