package net.pcal.fastback.common.repo;

import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

import static net.pcal.fastback.common.config.FastbackConfigKey.IS_BACKUP_ENABLED;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.utils.EnvironmentUtils.isNativeOk;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.NONE;
import static net.pcal.fastback.common.utils.Executor.checkCancelled;
import static net.pcal.fastback.common.utils.Executor.executor;

/** Shared newest-first snapshot listings for commands and argument suggestions. */
public final class SnapshotListings {

    private SnapshotListings() {
    }

    /** Guard failures remain distinguishable from an empty, successfully loaded repository. */
    public static final class Unavailable extends RuntimeException {
        private final UserMessage message;

        private Unavailable(UserMessage message) {
            super(message.toString());
            this.message = message;
        }

        public UserMessage message() {
            return message;
        }
    }

    public static CompletableFuture<List<SnapshotDetails>> get(Path world, boolean remote) {
        if (mod().isServerRestorePending()) {
            return CompletableFuture.failedFuture(new Unavailable(styledLocalized("fastback.chat.load-pending", ERROR)));
        }
        return SnapshotSuggestionsCache.get(world, remote, () -> load(world, remote));
    }

    private static CompletableFuture<List<SnapshotDetails>> load(Path world, boolean remote) {
        final CompletableFuture<List<SnapshotDetails>> result = new CompletableFuture<>();
        // Let each consumer decide whether guard failures should be displayed or remain silent.
        final UserLogger quiet = new UserLogger() {
            @Override
            public void message(UserMessage message) {
                result.completeExceptionally(new Unavailable(message));
            }

            @Override
            public void update(UserMessage message) {
            }
        };
        final FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                checkCancelled();
                final RepoFactory factory = RepoFactory.rf();
                if (!factory.isGitRepo(world)) {
                    if (!isNativeOk(true, quiet, false)) return null;
                    throw new Unavailable(styledLocalized("fastback.chat.not-enabled", ERROR));
                }
                final List<SnapshotDetails> snapshots;
                try (final Repo repo = factory.load(world)) {
                    final GitConfig config = repo.getConfig();
                    if (!isNativeOk(config, quiet, false)) return null;
                    if (!config.getBoolean(IS_BACKUP_ENABLED)) {
                        throw new Unavailable(styledLocalized("fastback.chat.not-enabled", ERROR));
                    }
                    final List<SnapshotDetails> local = repo.getLocalSnapshotDetails();
                    if (remote) {
                        final var localMetadata = new HashMap<String, SnapshotDetails>();
                        local.forEach(details -> localMetadata.put(details.id().getBranchName(), details));
                        snapshots = repo.getRemoteSnapshots().stream().map(id ->
                                new SnapshotDetails(id, localMetadata.containsKey(id.getBranchName())
                                        ? localMetadata.get(id.getBranchName()).metadata() : null)).toList();
                    } else {
                        snapshots = local;
                    }
                }
                checkCancelled();
                result.complete(snapshots.stream().sorted(Comparator.comparing(SnapshotDetails::id).reversed()).toList());
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
            return null;
        });
        // Timeout, invalidation and lifecycle cancellation interrupt native Git so it cleans up children.
        result.whenComplete((snapshots, failure) -> {
            if (failure != null) task.cancel(true);
        });
        try {
            executor().execute(NONE, quiet, task);
        } catch (Exception unavailable) {
            result.completeExceptionally(unavailable);
        }
        return result;
    }
}
