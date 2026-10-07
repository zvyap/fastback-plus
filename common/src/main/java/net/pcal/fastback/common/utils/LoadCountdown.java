package net.pcal.fastback.common.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Runs the load warning while the backup is being prepared on the operation worker. */
public final class LoadCountdown implements AutoCloseable {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "fastback-load-countdown");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Void> elapsed = new CompletableFuture<>();
    private volatile boolean closed;

    public LoadCountdown(final int seconds, final IntConsumer announce) {
        if (seconds < 0) throw new IllegalArgumentException("Load countdown cannot be negative");
        if (seconds == 0) {
            elapsed.complete(null);
            return;
        }
        try {
            announce.accept(seconds);
            final AtomicInteger remaining = new AtomicInteger(seconds);
            timer.scheduleAtFixedRate(() -> {
                synchronized (this) {
                    if (closed) return;
                    try {
                        final int left = remaining.decrementAndGet();
                        announce.accept(left);
                        if (left == 0) {
                            elapsed.complete(null);
                            timer.shutdown();
                        }
                    } catch (RuntimeException e) {
                        elapsed.completeExceptionally(e);
                        timer.shutdown();
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            timer.shutdownNow();
            throw e;
        }
    }

    public void await() throws InterruptedException, ExecutionException {
        elapsed.get();
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        synchronized (this) {
            elapsed.cancel(false);
        }
    }
}
