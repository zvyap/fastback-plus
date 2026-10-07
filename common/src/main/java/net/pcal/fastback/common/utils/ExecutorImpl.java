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

package net.pcal.fastback.common.utils;

import net.pcal.fastback.common.logging.UserLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static net.pcal.fastback.common.logging.SystemLogger.syslog;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.WARNING;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.Mod.mod;

/**
 * @author pcal
 * @since 0.2.0
 */
class ExecutorImpl implements Executor {

    private volatile ThreadPoolExecutor executor = null;

    private static final ThreadLocal<WriteTask> CURRENT_TASK = new ThreadLocal<>();
    private WriteTask exclusiveTask;

    private static class WriteTask {
        private volatile boolean cancelled;
        private boolean cancellable = true;
        private Thread worker;
    }

    static boolean isCancellationRequested() {
        final WriteTask task = CURRENT_TASK.get();
        return Thread.currentThread().isInterrupted() || (task != null && task.cancelled);
    }

    @Override
    public synchronized void execute(ExecutionLock lock, UserLogger ulog, Runnable runnable) {
        requireNonNull(lock, "lock");
        if (this.executor == null) throw new IllegalStateException("Executor not started");
        final Runnable task = () -> {
            if (mod().isServerRestorePending()) {
                ulog.message(styledLocalized("fastback.chat.load-pending", ERROR));
            } else {
                runnable.run();
            }
        };
        switch (lock) {
            case NONE:
            case WRITE_CONFIG: // revisit this
                this.executor.submit(task);
                break;
            case WRITE:
                if (this.exclusiveTask != null) {
                    ulog.message(styledLocalized("fastback.chat.thread-busy", ERROR));
                } else {
                    syslog().debug("executing " + runnable);
                    final WriteTask writeTask = new WriteTask();
                    this.exclusiveTask = writeTask;
                    try {
                        this.executor.submit(() -> runWriteTask(writeTask, task, ulog));
                    } catch (RuntimeException e) {
                        this.exclusiveTask = null;
                        throw e;
                    }
                }
                break;
            default:
                throw new IllegalStateException();
        }
    }

    private void runWriteTask(WriteTask writeTask, Runnable task, UserLogger ulog) {
        synchronized (this) {
            writeTask.worker = Thread.currentThread();
        }
        CURRENT_TASK.set(writeTask);
        try {
            Executor.checkCancelled();
            task.run();
            Executor.checkCancelled();
        } catch (CancellationException e) {
            ulog.message(styledLocalized("fastback.chat.operation-cancelled", WARNING));
        } finally {
            CURRENT_TASK.remove();
            // A cancelled Future reports completion before its worker exits. Keep ownership until this finally.
            synchronized (this) {
                writeTask.worker = null;
                if (this.exclusiveTask == writeTask) this.exclusiveTask = null;
            }
            if (writeTask.cancelled) Thread.interrupted();
        }
    }

    @Override
    public synchronized boolean cancel() {
        final WriteTask task = this.exclusiveTask;
        if (task == null || !task.cancellable) return false;
        if (task.cancelled) return true;
        task.cancelled = true;
        if (task.worker != null) task.worker.interrupt();
        return true;
    }

    @Override
    public synchronized boolean finishCancellableOperation(Runnable finish) {
        final WriteTask task = CURRENT_TASK.get();
        if (task == null || task != this.exclusiveTask) throw new IllegalStateException("No current WRITE task");
        if (task.cancelled || Thread.currentThread().isInterrupted()) return false;
        task.cancellable = false;
        finish.run();
        return true;
    }

    @Override
    public synchronized int getActiveCount() {
        return this.executor == null ? 0 : this.executor.getActiveCount();
    }

    @Override
    public synchronized void start() {
        if (this.executor != null) throw new IllegalStateException("Executor already started");
        this.executor = new ThreadPoolExecutor(0, 3, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    public void stop() {
        final ThreadPoolExecutor pool = this.executor;
        if (pool == null) return;
        shutdownExecutor(pool);
        if (!pool.isTerminated()) {
            throw new IllegalStateException("Backup tasks did not stop; refusing to release the world");
        }
        synchronized (this) {
            if (this.executor == pool) this.executor = null;
        }
    }

    /**
     * Lifted straight from the docs:
     * https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
     */
    private static void shutdownExecutor(final ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(5, TimeUnit.MINUTES))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
