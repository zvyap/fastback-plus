package net.pcal.fastback.common.repo;

import net.pcal.fastback.common.config.GitConfig;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

/** Cached newest-first IDs, with metadata read only for displayed pages or matching suggestions. */
public final class SnapshotListings {

    public static final int PAGE_SIZE = SnapshotCache.PAGE_SIZE;

    public record Page(int page, int total, List<SnapshotDetails> entries) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

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

    public static CompletableFuture<List<SnapshotId>> index(Path world, boolean remote) {
        try {
            return catalog(world, remote).ids();
        } catch (Exception unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    public static CompletableFuture<Page> page(Path world, boolean remote, int page) {
        try {
            final SnapshotCache.Catalog catalog = catalog(world, remote);
            return catalog.ids().thenCompose(ids -> {
                final long start = ((long) page - 1) * PAGE_SIZE;
                if (page < 1 || start >= ids.size()) {
                    return CompletableFuture.completedFuture(new Page(page, ids.size(), List.of()));
                }
                catalog.showing(ids, page);
                return read(catalog, world, slice(ids, page)).thenApply(details -> {
                    // The next page loads on the executor; the displayed page never waits for it.
                    if (start + PAGE_SIZE < ids.size()) read(catalog, world, slice(ids, page + 1));
                    return new Page(page, ids.size(), details);
                });
            });
        } catch (Exception unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    public static CompletableFuture<SnapshotDetails> details(Path world, boolean remote, String shortName) {
        try {
            final SnapshotCache.Catalog catalog = catalog(world, remote);
            return catalog.ids().thenCompose(ids -> {
                final List<SnapshotId> match = ids.stream().filter(id -> id.getShortName().equals(shortName)).limit(1).toList();
                return read(catalog, world, match).thenApply(details -> details.isEmpty() ? null : details.getFirst());
            });
        } catch (Exception unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    /** Bound metadata work for an empty prefix; a longer prefix still searches the entire index. */
    public static CompletableFuture<List<SnapshotDetails>> suggestions(Path world, boolean remote, String prefix, int limit) {
        try {
            final SnapshotCache.Catalog catalog = catalog(world, remote);
            final String normalized = prefix.toLowerCase(Locale.ROOT);
            return catalog.ids().thenCompose(ids -> read(catalog, world, ids.stream()
                    .filter(id -> id.getShortName().toLowerCase(Locale.ROOT).startsWith(normalized))
                    .limit(Math.max(0, limit)).toList()));
        } catch (Exception unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    private static SnapshotCache.Catalog catalog(Path world, boolean remote) {
        if (mod().isServerRestorePending()) {
            throw new Unavailable(styledLocalized("fastback.chat.load-pending", ERROR));
        }
        return SnapshotCache.get(world, remote, () -> load(world, repo ->
                (remote ? repo.getRemoteSnapshots() : repo.getLocalSnapshots()).stream()
                        .sorted(Comparator.reverseOrder()).toList()));
    }

    private static List<SnapshotId> slice(List<SnapshotId> ids, int page) {
        final int start = (page - 1) * PAGE_SIZE;
        return ids.subList(start, Math.min(start + PAGE_SIZE, ids.size()));
    }

    private static CompletableFuture<List<SnapshotDetails>> read(SnapshotCache.Catalog catalog, Path world,
                                                                List<SnapshotId> ids) {
        return catalog.details(ids, selected -> load(world, repo -> repo.getSnapshotDetails(selected)));
    }

    private interface Read<T> {
        T execute(Repo repo) throws Exception;
    }

    private static <T> CompletableFuture<T> load(Path world, Read<T> operation) {
        final CompletableFuture<T> result = new CompletableFuture<>();
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
                final T value;
                try (final Repo repo = factory.load(world)) {
                    final GitConfig config = repo.getConfig();
                    if (!isNativeOk(config, quiet, false)) return null;
                    if (!config.getBoolean(IS_BACKUP_ENABLED)) {
                        throw new Unavailable(styledLocalized("fastback.chat.not-enabled", ERROR));
                    }
                    value = operation.execute(repo);
                }
                checkCancelled();
                result.complete(value);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
            return null;
        });
        // Timeout, invalidation and lifecycle cancellation interrupt native Git so it cleans up children.
        result.whenComplete((value, failure) -> {
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
