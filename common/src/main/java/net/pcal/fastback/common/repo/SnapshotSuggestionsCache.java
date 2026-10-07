package net.pcal.fastback.common.repo;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Shares snapshot listings across commands without opening Git on every keystroke. */
public final class SnapshotSuggestionsCache {

    private static final SnapshotSuggestionsCache SHARED = new SnapshotSuggestionsCache(System::nanoTime);
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final Map<Key, Entry> entries = new HashMap<>();
    private final LongSupplier clock;

    private record Key(Path world, boolean remote) {
        private Key {
            world = world.toAbsolutePath().normalize();
        }
    }

    private record Entry(long created, CompletableFuture<List<SnapshotDetails>> snapshots) {
    }

    SnapshotSuggestionsCache(LongSupplier clock) {
        this.clock = clock;
    }

    public static CompletableFuture<List<SnapshotDetails>> get(Path world, boolean remote,
                                                               Supplier<CompletableFuture<List<SnapshotDetails>>> loader) {
        return SHARED.load(world, remote, loader);
    }

    /** Invalidate before/after mutations, including scheduled backups and configuration changes. */
    public static void invalidate(Path world) {
        SHARED.evict(world);
    }

    public static void clear() {
        SHARED.reset();
    }

    synchronized CompletableFuture<List<SnapshotDetails>> load(Path world, boolean remote,
                                                               Supplier<CompletableFuture<List<SnapshotDetails>>> loader) {
        final Key key = new Key(world, remote);
        final long now = clock.getAsLong();
        final Entry cached = entries.get(key);
        if (cached != null && now - cached.created() < TTL_NANOS) return cached.snapshots();
        if (cached != null) cached.snapshots().complete(List.of());
        final Entry entry = new Entry(now, new CompletableFuture<>());
        entries.put(key, entry);
        try {
            loader.get().orTimeout(5, TimeUnit.SECONDS).thenApply(List::copyOf).whenComplete((snapshots, failure) -> {
                synchronized (SnapshotSuggestionsCache.this) {
                    if (failure != null) entries.remove(key, entry);
                    final List<SnapshotDetails> result = failure == null && entries.get(key) == entry
                            ? snapshots : List.of();
                    entry.snapshots().complete(result);
                }
            });
        } catch (Exception failure) {
            entries.remove(key, entry);
            entry.snapshots().complete(List.of());
        }
        return entry.snapshots();
    }

    synchronized void evict(Path world) {
        final Path normalized = world.toAbsolutePath().normalize();
        final var removed = new java.util.ArrayList<Entry>();
        final var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            if (entry.getKey().world().equals(normalized)) {
                iterator.remove();
                removed.add(entry.getValue());
            }
        }
        removed.forEach(entry -> entry.snapshots().complete(List.of()));
    }

    synchronized void reset() {
        final List<Entry> old = List.copyOf(entries.values());
        entries.clear();
        old.forEach(entry -> entry.snapshots().complete(List.of()));
    }
}
