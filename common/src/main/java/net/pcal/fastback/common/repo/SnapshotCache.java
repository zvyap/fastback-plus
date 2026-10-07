package net.pcal.fastback.common.repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One cache for snapshot indexes and metadata used by lists, views and suggestions. */
public final class SnapshotCache {

    static final int PAGE_SIZE = 8;
    private static final SnapshotCache SHARED = new SnapshotCache(System::nanoTime);
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_METADATA = 256;
    private final Map<Key, Catalog> entries = new HashMap<>();
    private final LongSupplier clock;

    private record Key(Path world, boolean remote) {
        private Key {
            world = world.toAbsolutePath().normalize();
        }
    }

    SnapshotCache(LongSupplier clock) {
        this.clock = clock;
    }

    static Catalog get(Path world, boolean remote, Supplier<CompletableFuture<List<SnapshotId>>> loader) {
        return SHARED.load(world, remote, loader);
    }

    /** Invalidate before/after mutations, including scheduled backups and configuration changes. */
    public static void invalidate(Path world) {
        SHARED.evict(world);
    }

    public static void clear() {
        SHARED.reset();
    }

    synchronized Catalog load(Path world, boolean remote, Supplier<CompletableFuture<List<SnapshotId>>> loader) {
        final Key key = new Key(world, remote);
        final long now = clock.getAsLong();
        final Catalog cached = entries.get(key);
        if (cached != null && now - cached.created < TTL_NANOS) return cached;
        if (cached != null) discard(cached);
        final Catalog catalog = new Catalog(now);
        entries.put(key, catalog);
        catalog.ids.whenComplete((ids, failure) -> {
            if (failure != null) {
                synchronized (SnapshotCache.this) {
                    if (!catalog.active) return;
                    entries.remove(key, catalog);
                    discard(catalog);
                }
            }
        });
        try {
            final CompletableFuture<List<SnapshotId>> loading = loader.get();
            catalog.loading.add(loading);
            loading.orTimeout(5, TimeUnit.SECONDS).thenApply(List::copyOf).whenComplete((ids, failure) -> {
                synchronized (SnapshotCache.this) {
                    catalog.loading.remove(loading);
                    if (failure != null) {
                        entries.remove(key, catalog);
                        catalog.ids.completeExceptionally(failure);
                        discard(catalog);
                    } else if (catalog.active && entries.get(key) == catalog) {
                        catalog.ids.complete(ids);
                    } else {
                        discard(catalog);
                    }
                }
            });
        } catch (Exception failure) {
            entries.remove(key, catalog);
            catalog.ids.completeExceptionally(failure);
            discard(catalog);
        }
        return catalog;
    }

    final class Catalog {
        private final long created;
        private boolean active = true;
        private final CompletableFuture<List<SnapshotId>> ids = new CompletableFuture<>();
        private final Map<String, CompletableFuture<SnapshotDetails>> metadata = new LinkedHashMap<>(16, .75f, true);
        private final Set<String> retained = new HashSet<>();
        private final Set<CompletableFuture<?>> loading = new HashSet<>();

        private Catalog(long created) {
            this.created = created;
        }

        CompletableFuture<List<SnapshotId>> ids() {
            return ids;
        }

        /** Always retain page one and both neighbours of the displayed page. */
        void showing(List<SnapshotId> snapshots, int page) {
            synchronized (SnapshotCache.this) {
                retained.clear();
                snapshots.stream().limit(PAGE_SIZE).forEach(id -> retained.add(id.getBranchName()));
                final int from = Math.max(0, page - 2) * PAGE_SIZE;
                final int to = (int) Math.min(snapshots.size(), (long) (page + 1) * PAGE_SIZE);
                snapshots.subList(Math.min(from, snapshots.size()), to)
                        .forEach(id -> retained.add(id.getBranchName()));
                trim();
            }
        }

        CompletableFuture<List<SnapshotDetails>> details(List<SnapshotId> snapshots,
                Function<List<SnapshotId>, CompletableFuture<List<SnapshotDetails>>> loader) {
            synchronized (SnapshotCache.this) {
                if (!active) return CompletableFuture.failedFuture(invalidated());
                final var promises = new ArrayList<CompletableFuture<SnapshotDetails>>(snapshots.size());
                final var missing = new ArrayList<SnapshotId>();
                final var newPromises = new ArrayList<CompletableFuture<SnapshotDetails>>();
                for (final SnapshotId snapshot : snapshots) {
                    CompletableFuture<SnapshotDetails> promise = metadata.get(snapshot.getBranchName());
                    if (promise == null) {
                        promise = new CompletableFuture<>();
                        metadata.put(snapshot.getBranchName(), promise);
                        missing.add(snapshot);
                        newPromises.add(promise);
                    }
                    promises.add(promise);
                }
                trim();
                if (!missing.isEmpty()) {
                    try {
                        final CompletableFuture<List<SnapshotDetails>> batch = loader.apply(List.copyOf(missing));
                        loading.add(batch);
                        batch.orTimeout(5, TimeUnit.SECONDS).thenApply(List::copyOf).whenComplete((details, failure) -> {
                            synchronized (SnapshotCache.this) {
                                loading.remove(batch);
                                final Map<String, SnapshotDetails> byBranch = new HashMap<>();
                                if (failure == null) details.forEach(detail -> byBranch.put(detail.id().getBranchName(), detail));
                                for (int i = 0; i < missing.size(); i++) {
                                    final SnapshotId id = missing.get(i);
                                    final CompletableFuture<SnapshotDetails> promise = newPromises.get(i);
                                    if (!active || metadata.get(id.getBranchName()) != promise) continue;
                                    if (failure == null) {
                                        promise.complete(byBranch.getOrDefault(id.getBranchName(), new SnapshotDetails(id, null)));
                                    } else {
                                        metadata.remove(id.getBranchName());
                                        promise.completeExceptionally(failure);
                                    }
                                }
                                trim();
                            }
                        });
                    } catch (Exception failure) {
                        for (int i = 0; i < missing.size(); i++) {
                            metadata.remove(missing.get(i).getBranchName(), newPromises.get(i));
                            newPromises.get(i).completeExceptionally(failure);
                        }
                    }
                }
                return CompletableFuture.allOf(promises.toArray(CompletableFuture[]::new))
                        .thenApply(ignored -> promises.stream().map(CompletableFuture::join).toList());
            }
        }

        private void trim() {
            final var iterator = metadata.entrySet().iterator();
            while (metadata.size() > MAX_METADATA && iterator.hasNext()) {
                final var entry = iterator.next();
                // Pending readers keep their promises; the bound applies after their batch completes.
                if (retained.contains(entry.getKey()) || !entry.getValue().isDone()) continue;
                iterator.remove();
            }
        }
    }

    synchronized void evict(Path world) {
        final Path normalized = world.toAbsolutePath().normalize();
        final var removed = new ArrayList<Catalog>();
        entries.entrySet().removeIf(entry -> {
            if (!entry.getKey().world().equals(normalized)) return false;
            removed.add(entry.getValue());
            return true;
        });
        removed.forEach(this::discard);
    }

    synchronized void reset() {
        final List<Catalog> old = List.copyOf(entries.values());
        entries.clear();
        old.forEach(this::discard);
    }

    private void discard(Catalog catalog) {
        catalog.active = false;
        catalog.ids.completeExceptionally(invalidated());
        List.copyOf(catalog.metadata.values()).forEach(promise -> promise.completeExceptionally(invalidated()));
        List.copyOf(catalog.loading).forEach(future -> future.cancel(true));
        catalog.loading.clear();
        catalog.metadata.clear();
    }

    private static CancellationException invalidated() {
        return new CancellationException("Snapshot cache invalidated");
    }
}
