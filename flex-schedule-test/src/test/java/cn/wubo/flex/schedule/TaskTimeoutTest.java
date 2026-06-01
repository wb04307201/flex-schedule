package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import cn.wubo.flex.schedule.exception.ExecutionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskTimeoutTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(4);
        taskScheduler.setThreadNamePrefix("test-timeout-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void timeoutRunnable_completesWithinTimeout_shouldSucceed() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        TimeoutRunnable runnable = new TimeoutRunnable("fast", () -> {
            completed.set(true);
            latch.countDown();
        }, Duration.ofSeconds(5));

        runnable.run();
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertTrue(completed.get());
    }

    @Test
    void timeoutRunnable_exceedsTimeout_shouldThrow() {
        TimeoutRunnable runnable = new TimeoutRunnable("slow", () -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(100));

        assertThrows(ExecutionTimeoutException.class, runnable::run);
    }

    @Test
    void timeoutRunnable_propagatesRuntimeException() {
        TimeoutRunnable runnable = new TimeoutRunnable("error", () -> {
            throw new RuntimeException("task error");
        }, Duration.ofSeconds(5));

        RuntimeException ex = assertThrows(RuntimeException.class, runnable::run);
        assertEquals("task error", ex.getMessage());
    }

    @Test
    void addCronTask_withTimeout_shouldRegister() {
        registrar.addCronTask("timeoutCron", "* * * * * *", Duration.ofSeconds(5), () -> {});
        assertTrue(registrar.exists("timeoutCron"));
    }

    @Test
    void addFixedDelayTask_withTimeout_shouldRegister() {
        registrar.addFixedDelayTask("timeoutDelay", Duration.ofSeconds(10), Duration.ZERO,
                Duration.ofSeconds(5), () -> {});
        assertTrue(registrar.exists("timeoutDelay"));
    }

    @Test
    void addFixedRateTask_withTimeout_shouldRegister() {
        registrar.addFixedRateTask("timeoutRate", Duration.ofSeconds(10), Duration.ZERO,
                Duration.ofSeconds(5), () -> {});
        assertTrue(registrar.exists("timeoutRate"));
    }

    @Test
    void task_timeoutRecordsErrorInHistory() throws InterruptedException {
        InMemoryExecutionHistory history = new InMemoryExecutionHistory(10);
        registrar.setExecutionHistory(history);

        CountDownLatch listenerLatch = new CountDownLatch(1);
        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void onError(String taskName, Throwable error) {
                if ("timeoutHist".equals(taskName)) {
                    listenerLatch.countDown();
                }
            }
        });

        registrar.addFixedRateTask("timeoutHist", Duration.ofSeconds(60), Duration.ZERO,
                Duration.ofMillis(100), () -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        assertTrue(listenerLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        List<ExecutionRecord> records = history.getHistory("timeoutHist", 10);
        assertFalse(records.isEmpty());
        assertFalse(records.get(0).success());
    }

    // ─── Task Chain Tests ────────────────────────────────────────────

    @Test
    void taskChain_executesInOrder() throws Exception {
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger step1Order = new AtomicInteger(-1);
        AtomicInteger step2Order = new AtomicInteger(-1);
        AtomicInteger step3Order = new AtomicInteger(-1);

        CountDownLatch latch = new CountDownLatch(1);

        TaskChain.create(registrar)
                .then("step1", () -> step1Order.set(order.incrementAndGet()))
                .then("step2", () -> step2Order.set(order.incrementAndGet()))
                .then("step3", () -> {
                    step3Order.set(order.incrementAndGet());
                    latch.countDown();
                })
                .execute();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, step1Order.get());
        assertEquals(2, step2Order.get());
        assertEquals(3, step3Order.get());
    }

    @Test
    void taskChain_stopsOnFailure() throws Exception {
        AtomicBoolean step3Executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        TaskChain.create(registrar)
                .then("step1", () -> {})
                .then("step2", () -> { throw new RuntimeException("fail"); })
                .then("step3", () -> step3Executed.set(true))
                .execute()
                .whenComplete((v, e) -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(step3Executed.get());
    }

    @Test
    void taskChain_withTimeout_shouldEnforce() throws Exception {
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        TaskChain.create(registrar)
                .then("slow", Duration.ofMillis(100), () -> {
                    try {
                        Thread.sleep(5000);
                        completed.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .execute()
                .whenComplete((v, e) -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(completed.get());
    }

    @Test
    void taskChain_schedule_executesAfterDelay() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        TaskChain.create(registrar)
                .then("delayed", () -> {
                    executed.set(true);
                    latch.countDown();
                })
                .schedule("chainDelay", Duration.ofMillis(200));

        assertFalse(executed.get());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }

    @Test
    void executionTimeoutException_containsDetails() {
        ExecutionTimeoutException ex = new ExecutionTimeoutException("myTask", Duration.ofSeconds(30));
        assertEquals("myTask", ex.getTaskName());
        assertEquals(Duration.ofSeconds(30), ex.getTimeout());
        assertTrue(ex.getMessage().contains("myTask"));
        assertTrue(ex.getMessage().contains("30"));
    }
}
