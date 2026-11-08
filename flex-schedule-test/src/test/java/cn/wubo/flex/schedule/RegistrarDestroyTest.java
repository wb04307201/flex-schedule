package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar.ScheduledTaskEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Destroy-resilience test for {@link FlexScheduledTaskRegistrar}
 * (1.3.0 test-completion plan, scenario A12).
 * <p>
 * Pins that {@code destroy()} cancels every task inside a per-entry
 * try/catch: one task whose cancellation throws must be logged and skipped,
 * while the other tasks are still cancelled, the task map is still cleared,
 * and the scheduler executor is still shut down.
 * </p>
 * <p>
 * Construction note: a throwing cancel cannot be produced through the public
 * registration API (the cancel actions are closures over real futures that
 * never throw), so the entry's {@code cancelAction} is swapped via reflection
 * on the private {@code taskMap} field — the same seam
 * {@code MisfirePolicyBehaviorTest.readEntry(...)} already uses. The tasks are
 * registered through the real public API first; only the cancel closure is
 * replaced, so destroy()'s iteration, catch branch, state clearing, and
 * executor shutdown all run against genuine registrar state.
 * </p>
 */
class RegistrarDestroyTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("a12-sched-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        // Pending far-future one-shot/delayed tasks must not block
        // awaitTermination() when their cancel action was sabotaged to throw.
        taskScheduler.getScheduledThreadPoolExecutor()
                .setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 2);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ScheduledTaskEntry> taskMap() throws Exception {
        Field taskMapField = FlexScheduledTaskRegistrar.class.getDeclaredField("taskMap");
        taskMapField.setAccessible(true);
        return (Map<String, ScheduledTaskEntry>) taskMapField.get(registrar);
    }

    @Test
    void destroy_oneCancelThrows_othersStillCancelledAndShutdown() throws Exception {
        registrar.addFixedDelayTask("boomTask", Duration.ofSeconds(60), Duration.ZERO, () -> {});
        registrar.addFixedDelayTask("okTask", Duration.ofSeconds(60), Duration.ZERO, () -> {});
        assertTrue(registrar.exists("boomTask"));
        assertTrue(registrar.exists("okTask"));

        Map<String, ScheduledTaskEntry> taskMap = taskMap();

        // "boomTask": cancel throws a RuntimeException (simulates a broken future stub).
        ScheduledTaskEntry boom = taskMap.get("boomTask");
        AtomicInteger boomCancelAttempts = new AtomicInteger();
        taskMap.put("boomTask", new ScheduledTaskEntry(
                () -> {
                    boomCancelAttempts.incrementAndGet();
                    throw new RuntimeException("cancel boom");
                },
                boom.taskType(), boom.schedule(), boom.retryPolicy(), boom.oneShot(), boom.createdAt()));

        // "okTask": keep the REAL cancel action but observe that it actually ran.
        ScheduledTaskEntry ok = taskMap.get("okTask");
        AtomicBoolean okCancelled = new AtomicBoolean();
        taskMap.put("okTask", new ScheduledTaskEntry(
                () -> {
                    ok.cancelAction().run();
                    okCancelled.set(true);
                },
                ok.taskType(), ok.schedule(), ok.retryPolicy(), ok.oneShot(), ok.createdAt()));

        // Destroy must absorb the throwing cancel without aborting the loop.
        // (Entry iteration order is not guaranteed, so both the "throw first"
        // and "throw last" orderings are exercised across runs.)
        assertDoesNotThrow(registrar::destroy,
                "destroy() must not propagate an exception thrown by a task's cancelAction");

        assertEquals(1, boomCancelAttempts.get(), "the throwing cancel action must have been attempted");
        assertTrue(okCancelled.get(), "the other task must still be cancelled when one cancel throws");
        assertFalse(registrar.exists("okTask"), "task map must be cleared");
        assertFalse(registrar.exists("boomTask"), "task map must be cleared");
        assertTrue(registrar.listTasks().isEmpty(), "no task may remain registered after destroy");
        assertTrue(taskScheduler.getScheduledThreadPoolExecutor().isShutdown(),
                "the scheduler executor must still be shut down after a throwing cancel");
    }
}
