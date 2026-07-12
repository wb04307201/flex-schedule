package cn.wubo.flex.schedule.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutExecutorManagerTest {

    @BeforeEach
    @AfterEach
    void resetManager() {
        // Ensure each test starts/ends with a clean shared executor
        TimeoutExecutorManager.shutdown();
    }

    @Test
    void getExecutor_createsNewInstance_whenShutdown() {
        ExecutorService first = TimeoutExecutorManager.getExecutor();
        assertThat(first).isNotNull();
        assertThat(first.isShutdown()).isFalse();

        // After shutdown the next call must produce a fresh (non-shutdown) instance.
        TimeoutExecutorManager.shutdown();
        ExecutorService second = TimeoutExecutorManager.getExecutor();
        assertThat(second).isNotSameAs(first);
        assertThat(second.isShutdown()).isFalse();
    }

    @Test
    void getExecutor_returnsSameInstance_whenAlive() {
        ExecutorService a = TimeoutExecutorManager.getExecutor();
        ExecutorService b = TimeoutExecutorManager.getExecutor();
        assertThat(b).isSameAs(a);
    }

    @Test
    void shutdown_isIdempotent_andClearsSharedInstance() {
        ExecutorService first = TimeoutExecutorManager.getExecutor();
        TimeoutExecutorManager.shutdown();

        // Calling shutdown a second time must not throw even though the executor is already gone.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(TimeoutExecutorManager::shutdown);

        ExecutorService after = TimeoutExecutorManager.getExecutor();
        assertThat(after).isNotSameAs(first);
    }

    @Test
    void getExecutor_concurrentAccess_doesNotCreateMultipleExecutors() throws Exception {
        int threadCount = 16;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReferenceArray<ExecutorService> results =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                results.set(idx, TimeoutExecutorManager.getExecutor());
            }).start();
        }
        ready.await();
        go.countDown();
        Thread.sleep(100); // let all threads settle

        ExecutorService expected = results.get(0);
        assertThat(expected).isNotNull();
        for (int i = 1; i < threadCount; i++) {
            assertThat(results.get(i)).isSameAs(expected);
        }
    }
}