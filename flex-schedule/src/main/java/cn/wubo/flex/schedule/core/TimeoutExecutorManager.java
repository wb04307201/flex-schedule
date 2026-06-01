package cn.wubo.flex.schedule.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a shared thread pool for timeout execution.
 * This avoids creating a new thread pool for each timeout task.
 */
public class TimeoutExecutorManager {

    private static volatile ExecutorService sharedExecutor;
    private static final Object lock = new Object();
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private TimeoutExecutorManager() {
    }

    /**
     * Gets the shared executor service, creating it if necessary.
     */
    public static ExecutorService getExecutor() {
        if (sharedExecutor == null || sharedExecutor.isShutdown()) {
            synchronized (lock) {
                if (sharedExecutor == null || sharedExecutor.isShutdown()) {
                    sharedExecutor = createExecutor(DEFAULT_POOL_SIZE);
                }
            }
        }
        return sharedExecutor;
    }

    /**
     * Creates a new executor with the specified pool size.
     */
    private static ExecutorService createExecutor(int poolSize) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "flex-schedule-timeout-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(poolSize, threadFactory);
    }

    /**
     * Shuts down the shared executor and waits for termination.
     * Should be called during application shutdown.
     */
    public static void shutdown() {
        synchronized (lock) {
            if (sharedExecutor != null && !sharedExecutor.isShutdown()) {
                sharedExecutor.shutdown();
                try {
                    if (!sharedExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        sharedExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    sharedExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                sharedExecutor = null;
            }
        }
    }

    /**
     * Resets the manager (primarily for testing).
     */
    static void reset() {
        shutdown();
    }
}
