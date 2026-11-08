package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.TaskChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-propagation and executor-routing tests for {@link TaskChain}
 * (1.3.0 test-completion plan, scenarios A1/A2/A3).
 * <p>
 * These pin the contract that a failing chain step completes the returned
 * future exceptionally (with the original exception reachable in the cause
 * chain), that later steps never run after a failure, that {@code withExecutor}
 * genuinely routes every step through the caller's executor, and that the
 * delayed {@code schedule(...)} bridge future carries the failure across.
 * </p>
 */
class TaskChainTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-chain-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    /** Walks the cause chain looking for the exact exception instance thrown by a chain step. */
    private static Throwable unwrapTo(Throwable t, Object sentinel) {
        while (t != null) {
            if (t == sentinel) {
                return t;
            }
            t = t.getCause();
        }
        return null;
    }

    // ─── A1 ──────────────────────────────────────────────────────────

    @Test
    void execute_failurePropagatesToFuture() throws Exception {
        RuntimeException step2Error = new RuntimeException("step2-boom");
        AtomicBoolean step1Ran = new AtomicBoolean();
        AtomicBoolean step3Ran = new AtomicBoolean();

        CompletableFuture<Void> future = TaskChain.create(registrar)
                .then("step1", () -> step1Ran.set(true))
                .then("step2", () -> { throw step2Error; })
                .then("step3", () -> step3Ran.set(true))
                .execute();

        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS),
                "the chain future must complete exceptionally when a step fails");
        assertTrue(future.isCompletedExceptionally());

        assertNotNull(unwrapTo(ee, step2Error),
                "the thrown step-2 exception must be reachable in the future's cause chain, got: " + ee);
        assertTrue(step1Ran.get(), "the step before the failure must have run");
        assertFalse(step3Ran.get(), "steps after the failure must NOT run");
    }

    // ─── A2 ──────────────────────────────────────────────────────────

    @Test
    void withExecutor_customExecutorRunsSteps() throws Exception {
        AtomicInteger executorSubmissions = new AtomicInteger();
        List<String> stepThreads = new CopyOnWriteArrayList();
        CountDownLatch allStepsDone = new CountDownLatch(3);

        // Single-thread executor with a distinctive name so we can prove routing.
        ExecutorService chainPool =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "custom-chain-worker"));
        try {
            Executor recording = task -> {
                executorSubmissions.incrementAndGet();
                chainPool.execute(task);
            };

            Runnable recordThread = () -> {
                stepThreads.add(Thread.currentThread().getName());
                allStepsDone.countDown();
            };

            CompletableFuture<Void> future = TaskChain.create(registrar)
                    .withExecutor(recording)
                    .then("step1", recordThread)
                    .then("step2", recordThread)
                    .then("step3", recordThread)
                    .execute();

            assertNull(future.get(5, TimeUnit.SECONDS));
            assertTrue(allStepsDone.await(5, TimeUnit.SECONDS), "all three steps must run");

            assertEquals(3, executorSubmissions.get(),
                    "every chain step must be submitted through the custom executor");
            assertEquals(List.of("custom-chain-worker", "custom-chain-worker", "custom-chain-worker"),
                    stepThreads, "every step must run on the custom executor's thread");
            assertNotEquals(Thread.currentThread().getName(), stepThreads.get(0),
                    "steps must not run inline on the registering thread");
        } finally {
            chainPool.shutdownNow();
        }
    }

    // ─── A3 ──────────────────────────────────────────────────────────

    @Test
    void schedule_stepFails_futureCompletesExceptionally() throws Exception {
        RuntimeException chainError = new RuntimeException("scheduled-chain-boom");
        AtomicBoolean stepAfterFailureRan = new AtomicBoolean();
        CountDownLatch chainStarted = new CountDownLatch(1);

        CompletableFuture<Void> bridged = TaskChain.create(registrar)
                .then("trigger", () -> chainStarted.countDown())
                .then("boom", () -> { throw chainError; })
                .then("never", () -> stepAfterFailureRan.set(true))
                .schedule("a3Chain", Duration.ofMillis(100));

        assertTrue(chainStarted.await(5, TimeUnit.SECONDS),
                "scheduled chain must start after its delay");

        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> bridged.get(5, TimeUnit.SECONDS),
                "the bridge future must complete exceptionally when a scheduled step fails");
        assertTrue(bridged.isCompletedExceptionally());
        assertNotNull(unwrapTo(ee, chainError),
                "the bridge future's cause chain must contain the step exception, got: " + ee);
        assertFalse(stepAfterFailureRan.get(), "steps after the failure must NOT run");
    }
}
