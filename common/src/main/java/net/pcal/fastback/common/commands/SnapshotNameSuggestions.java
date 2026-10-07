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
package net.pcal.fastback.common.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotListings;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.pcal.fastback.common.mod.Mod.mod;

final class SnapshotNameSuggestions implements SuggestionProvider<CommandSourceStack> {

    private final boolean remote;

    SnapshotNameSuggestions(boolean remote) {
        this.remote = remote;
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context,
                                                         SuggestionsBuilder builder) {
        try {
            final Path world = mod().getWorldDirectory();
            // ponytail: cap popup metadata work; type a narrower prefix to reach older matching IDs.
            return SnapshotListings.suggestions(world, remote, builder.getRemainingLowerCase(), 100)
                    .thenApply(snapshots -> build(snapshots, remote, builder))
                    .exceptionally(failure -> builder.build());
        } catch (Exception unavailable) {
            return builder.buildFuture();
        }
    }

    static Suggestions build(List<SnapshotDetails> snapshots, boolean remote, SuggestionsBuilder builder) {
        final String prefix = builder.getRemainingLowerCase();
        for (final SnapshotDetails details : snapshots) {
            final String name = details.id().getShortName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                builder.suggest(name, SnapshotArgument.tooltip(details, remote));
            }
        }
        return builder.build();
    }
}
