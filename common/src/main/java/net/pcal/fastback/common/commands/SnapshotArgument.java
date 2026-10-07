package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.repo.SnapshotDetails;

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

    static Component tooltip(SnapshotDetails details, boolean remote) {
        return SnapshotPresentation.tooltip(details, remote, true);
    }
}
