package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.DefaultFlexScheduledTaskService;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.RetryPolicy;
import cn.wubo.flex.schedule.core.TaskDetail;
import cn.wubo.flex.schedule.core.TaskExecutionListener;
import cn.wubo.flex.schedule.core.TaskInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduledTaskServiceTest {

    private FlexScheduledTaskService service;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-service-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
        service = new DefaultFlexScheduledTaskService(registrar);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    @Test
    void add_shouldRegisterCronTask() {
        service.add("cronJob", "0 * * * * *", () -> {});
        assertTrue(service.exists("cronJob"));
    }

    @Test
    void addFixedDelayTask_shouldRegister() {
        service.addFixedDelayTask("delayJob", 10, 0, () -> {});
        assertTrue(service.exists("delayJob"));
    }

    @Test
    void addFixedRateTask_shouldRegister() {
        service.addFixedRateTask("rateJob", 10, 0, () -> {});
        assertTrue(service.exists("rateJob"));
    }

    @Test
    void cancel_shouldRemoveTask() {
        service.add("toCancel", "* * * * * *", () -> {});
        assertTrue(service.exists("toCancel"));

        service.cancel("toCancel");
        assertFalse(service.exists("toCancel"));
    }

    @Test
    void exists_shouldReturnFalseForNonExistent() {
        assertFalse(service.exists("nope"));
    }

    @Test
    void listTasks_shouldReturnAllTasks() {
        service.add("t1", "* * * * * *", () -> {});
        service.addFixedDelayTask("t2", 10, 0, () -> {});
        service.addFixedRateTask("t3", 10, 0, () -> {});

        List<TaskInfo> tasks = service.listTasks();
        assertEquals(3, tasks.size());
    }

    // ─── Duration Overloads ───────────────────────────────────────────

    @Test
    void addFixedDelayTask_duration_shouldRegister() {
        service.addFixedDelayTask("durDelay", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        assertTrue(service.exists("durDelay"));
    }

    @Test
    void addFixedRateTask_duration_shouldRegister() {
        service.addFixedRateTask("durRate", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        assertTrue(service.exists("durRate"));
    }

    // ─── Retry Overloads ─────────────────────────────────────────────

    @Test
    void add_withRetryPolicy_shouldRegister() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(1));
        service.add("retryCron", "* * * * * *", () -> {}, policy);
        assertTrue(service.exists("retryCron"));
    }

    @Test
    void addFixedDelayTask_withRetryPolicy_shouldRegister() {
        RetryPolicy policy = RetryPolicy.fixed(2, Duration.ofMillis(100));
        service.addFixedDelayTask("retryDelay", Duration.ofSeconds(10), Duration.ZERO, () -> {}, policy);
        assertTrue(service.exists("retryDelay"));
    }

    @Test
    void addFixedRateTask_withRetryPolicy_shouldRegister() {
        RetryPolicy policy = RetryPolicy.exponential(2, Duration.ofMillis(100));
        service.addFixedRateTask("retryRate", Duration.ofSeconds(10), Duration.ZERO, () -> {}, policy);
        assertTrue(service.exists("retryRate"));
    }

    // ─── Replace Methods ─────────────────────────────────────────────

    @Test
    void replaceCronTask_existing_shouldReturnTrue() {
        service.add("replaceMe", "* * * * * *", () -> {});
        boolean replaced = service.replaceCronTask("replaceMe", "0 * * * * *", () -> {});
        assertTrue(replaced);
        assertTrue(service.exists("replaceMe"));
    }

    @Test
    void replaceCronTask_nonExisting_shouldReturnFalse() {
        boolean replaced = service.replaceCronTask("newTask", "* * * * * *", () -> {});
        assertFalse(replaced);
        assertTrue(service.exists("newTask"));
    }

    @Test
    void replaceFixedDelayTask_shouldWork() {
        service.addFixedDelayTask("rd", 10, 0, () -> {});
        boolean replaced = service.replaceFixedDelayTask("rd", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
    }

    @Test
    void replaceFixedRateTask_shouldWork() {
        service.addFixedRateTask("rr", 10, 0, () -> {});
        boolean replaced = service.replaceFixedRateTask("rr", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
    }

    // ─── One-Shot Tasks ───────────────────────────────────────────────

    @Test
    void schedule_shouldRegister() {
        service.schedule("oneShot", Duration.ofSeconds(60), () -> {});
        assertTrue(service.exists("oneShot"));
    }

    // ─── getTaskDetail ────────────────────────────────────────────────

    @Test
    void getTaskDetail_found() {
        service.add("detail", "0 * * * * *", () -> {});
        Optional<TaskDetail> detail = service.getTaskDetail("detail");
        assertTrue(detail.isPresent());
        assertEquals("detail", detail.get().taskName());
        assertEquals("CRON", detail.get().taskType());
    }

    @Test
    void getTaskDetail_notFound() {
        Optional<TaskDetail> detail = service.getTaskDetail("nope");
        assertTrue(detail.isEmpty());
    }

    // ─── Listener Delegation ──────────────────────────────────────────

    @Test
    void addListener_shouldDelegate() {
        TaskExecutionListener listener = new TaskExecutionListener() {};
        assertDoesNotThrow(() -> service.addListener(listener));
    }

    @Test
    void removeListener_shouldDelegate() {
        TaskExecutionListener listener = new TaskExecutionListener() {};
        service.addListener(listener);
        assertDoesNotThrow(() -> service.removeListener(listener));
    }

    // ─── getTaskStatistics / getAllTaskStatistics ────────────────────

    @Test
    void getTaskStatistics_noRecords_returnsEmpty() {
        service.addFixedDelayTask("nohistory", 10, 0, () -> {});

        Optional<cn.wubo.flex.schedule.core.TaskStatistics> stats =
            service.getTaskStatistics("nohistory");

        assertTrue(stats.isEmpty());
    }

    @Test
    void getTaskStatistics_withRecords_returnsPopulated() throws InterruptedException {
        // Configure a real InMemoryExecutionHistory so we have records to aggregate.
        registrar.setExecutionHistory(new cn.wubo.flex.schedule.core.InMemoryExecutionHistory(50));

        AtomicInteger fires = new AtomicInteger();
        service.addFixedDelayTask("withhistory", Duration.ofMillis(30), Duration.ZERO, fires::incrementAndGet);

        // Wait until at least one execution has happened
        long deadline = System.currentTimeMillis() + 2000;
        while (fires.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        Optional<cn.wubo.flex.schedule.core.TaskStatistics> stats =
            service.getTaskStatistics("withhistory");
        assertTrue(stats.isPresent());
        assertTrue(stats.get().totalExecutions() >= 1);
    }

    @Test
    void getAllTaskStatistics_returnsOneEntryPerTask() throws InterruptedException {
        service.addFixedDelayTask("a", Duration.ofMinutes(15), Duration.ZERO, () -> {});
        service.addFixedDelayTask("b", Duration.ofMinutes(15), Duration.ZERO, () -> {});

        List<cn.wubo.flex.schedule.core.TaskStatistics> all = service.getAllTaskStatistics();

        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(s -> s.taskName().equals("a")));
        assertTrue(all.stream().anyMatch(s -> s.taskName().equals("b")));
    }
}
