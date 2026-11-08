package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionHistoryTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;
    private InMemoryExecutionHistory history;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("test-history-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        history = new InMemoryExecutionHistory(10);
        registrar.setExecutionHistory(history);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void successfulExecution_shouldRecordHistory() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("historyTask", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200); // Wait for history recording

        List<ExecutionRecord> records = history.getHistory("historyTask", 10);
        assertFalse(records.isEmpty());
        assertTrue(records.get(0).success());
        assertNull(records.get(0).error());
        assertEquals("historyTask", records.get(0).taskName());
        assertEquals("FIXED_RATE", records.get(0).taskType());
    }

    @Test
    void failedExecution_shouldRecordError() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("failHistoryTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            latch.countDown();
            throw new RuntimeException("test error");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        List<ExecutionRecord> records = history.getHistory("failHistoryTask", 10);
        assertFalse(records.isEmpty());
        assertFalse(records.get(0).success());
        assertEquals("test error", records.get(0).error());
    }

    @Test
    void getHistory_respectsLimit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(5);
        registrar.addFixedRateTask("limitTask", Duration.ofMillis(50), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        List<ExecutionRecord> records = history.getHistory("limitTask", 3);
        assertTrue(records.size() <= 3);
    }

    @Test
    void getAllHistory_returnsAllTasks() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        registrar.addFixedRateTask("task1", Duration.ofMillis(100), Duration.ZERO, latch1::countDown);
        registrar.addFixedRateTask("task2", Duration.ofMillis(100), Duration.ZERO, latch2::countDown);

        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        List<ExecutionRecord> all = history.getAllHistory(100);
        assertTrue(all.stream().anyMatch(r -> r.taskName().equals("task1")));
        assertTrue(all.stream().anyMatch(r -> r.taskName().equals("task2")));
    }

    // ─── A15: getAllHistory cross-task ordering by startTime DESC ─────

    @Test
    void getAllHistory_ordersAcrossTasksByStartTimeDescending() {
        InMemoryExecutionHistory store = new InMemoryExecutionHistory(10);
        java.time.Instant base = java.time.Instant.parse("2026-01-01T00:00:00Z");

        // Interleave tasks and insert deliberately out of chronological order.
        store.record(new ExecutionRecord("taskA", "CRON", base.plusSeconds(1), Duration.ofMillis(10), true, null));
        store.record(new ExecutionRecord("taskB", "FIXED_RATE", base.plusSeconds(3), Duration.ofMillis(10), true, null));
        store.record(new ExecutionRecord("taskA", "CRON", base.minusSeconds(2), Duration.ofMillis(10), false, "boom"));
        store.record(new ExecutionRecord("taskC", "ONE_SHOT", base.plusSeconds(2), Duration.ofMillis(10), true, null));

        List<ExecutionRecord> all = store.getAllHistory(100);
        assertEquals(4, all.size());
        assertEquals(
                List.of(base.plusSeconds(3), base.plusSeconds(2), base.plusSeconds(1), base.minusSeconds(2)),
                all.stream().map(ExecutionRecord::startTime).toList(),
                "getAllHistory must sort across tasks newest-first by startTime");
        assertEquals(List.of("taskB", "taskC", "taskA", "taskA"),
                all.stream().map(ExecutionRecord::taskName).toList());

        // limit keeps the newest prefix of the global ordering
        List<ExecutionRecord> top2 = store.getAllHistory(2);
        assertEquals(2, top2.size());
        assertEquals(base.plusSeconds(3), top2.get(0).startTime());
        assertEquals(base.plusSeconds(2), top2.get(1).startTime());
    }

    @Test
    void clear_specificTask_removesOnlyThatTask() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        registrar.addFixedRateTask("clear1", Duration.ofMillis(100), Duration.ZERO, latch1::countDown);
        registrar.addFixedRateTask("clear2", Duration.ofMillis(100), Duration.ZERO, latch2::countDown);

        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        history.clear("clear1");
        assertTrue(history.getHistory("clear1", 10).isEmpty());
        assertFalse(history.getHistory("clear2", 10).isEmpty());
    }

    @Test
    void clear_all_removesEverything() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("clearAll", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        history.clear(null);
        assertTrue(history.getAllHistory(100).isEmpty());
    }

    @Test
    void noopHistory_doesNotThrow() {
        ExecutionHistory.NOOP.record(new ExecutionRecord("t", "CRON", java.time.Instant.now(), Duration.ZERO, true, null));
        assertTrue(ExecutionHistory.NOOP.getHistory("t", 10).isEmpty());
        assertTrue(ExecutionHistory.NOOP.getAllHistory(10).isEmpty());
        assertDoesNotThrow(() -> ExecutionHistory.NOOP.clear("t"));
        assertDoesNotThrow(() -> ExecutionHistory.NOOP.clear(null));
    }

    @Test
    void inMemoryHistory_boundedByMaxRecords() {
        InMemoryExecutionHistory small = new InMemoryExecutionHistory(3);
        for (int i = 0; i < 10; i++) {
            small.record(new ExecutionRecord("bounded", "CRON", java.time.Instant.now(), Duration.ZERO, true, null));
        }
        List<ExecutionRecord> records = small.getHistory("bounded", 100);
        assertEquals(3, records.size());
    }

    @Test
    void inMemoryHistory_invalidMaxRecords_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionHistory(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryExecutionHistory(-1));
    }

    @Test
    void service_getExecutionHistory_delegates() throws InterruptedException {
        FlexScheduledTaskService service = new DefaultFlexScheduledTaskService(registrar);

        CountDownLatch latch = new CountDownLatch(1);
        service.addFixedRateTask("svcHist", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        List<ExecutionRecord> records = service.getExecutionHistory("svcHist", 10);
        assertFalse(records.isEmpty());
    }
}
