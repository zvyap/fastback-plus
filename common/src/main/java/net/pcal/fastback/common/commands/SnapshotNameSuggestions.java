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
import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage;
import net.pcal.fastback.common.repo.Repo;
import net.pcal.fastback.common.repo.RepoFactory;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotSuggestionsCache;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.pcal.fastback.common.config.FastbackConfigKey.IS_BACKUP_ENABLED;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.utils.EnvironmentUtils.isNativeOk;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.NONE;
import static net.pcal.fastback.common.utils.Executor.checkCancelled;
import static net.pcal.fastback.common.utils.Executor.executor;

final class SnapshotNameSuggestions implements SuggestionProvider<CommandSourceStack> {

    private final boolean remote;

    SnapshotNameSuggestions(boolean remote) {
        this.remote = remote;
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context,
                                                         SuggestionsBuilder builder) {
        try {
            if (mod().isServerRestorePending()) return builder.buildFuture();
            final Path world = mod().getWorldDirectory();
            return SnapshotSuggestionsCache.get(world, remote, () -> load(world))
                    .thenApply(snapshots -> build(snapshots, remote, builder))
                    .exceptionally(failure -> builder.build());
        } catch (Exception unavailable) {
            return builder.buildFuture();
        }
    }

    private CompletableFuture<List<SnapshotDetails>> load(Path world) {
        final CompletableFuture<List<SnapshotDetails>> result = new CompletableFuture<>();
        // Completion failures are silent; the next request can retry without sending chat messages.
        final UserLogger quiet = new UserLogger() {
            @Override
            public void message(UserMessage message) {
                result.complete(List.of());
            }

            @Override
            public void update(UserMessage message) {
            }
        };
        try {
            executor().execute(NONE, quiet, () -> {
                try {
                    checkCancelled();
                    final RepoFactory factory = RepoFactory.rf();
                    if (!factory.isGitRepo(world)) {
                        result.complete(List.of());
                        return;
                    }
                    try (final Repo repo = factory.load(world)) {
                        final GitConfig config = repo.getConfig();
                        if (!config.getBoolean(IS_BACKUP_ENABLED) || !isNativeOk(config, quiet, false)) {
                            result.complete(List.of());
                            return;
                        }
                        final List<SnapshotDetails> local = repo.getLocalSnapshotDetails();
                        if (remote) {
                            final var localMetadata = new HashMap<String, SnapshotDetails>();
                            local.forEach(details -> localMetadata.put(details.id().getBranchName(), details));
                            result.complete(repo.getRemoteSnapshots().stream().map(id ->
                                    new SnapshotDetails(id, localMetadata.containsKey(id.getBranchName())
                                            ? localMetadata.get(id.getBranchName()).metadata() : null)).toList());
                        } else {
                            result.complete(local);
                        }
                    }
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Exception unavailable) {
            result.completeExceptionally(unavailable);
        }
        return result;
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
