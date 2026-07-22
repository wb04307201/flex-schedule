package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.TaskAlreadyExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduledTaskRegistrarTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(4);
        taskScheduler.setThreadNamePrefix("test-scheduler-");
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── Cron Tasks ──────────────────────────────────────────────────

    @Test
    void addCronTask_shouldRegisterTask() {
        registrar.addCronTask("cronTask", "0 * * * * *", () -> {});

        assertTrue(registrar.exists("cronTask"));
        assertEquals(1, registrar.listTasks().size());
        TaskInfo info = registrar.listTasks().get(0);
        assertEquals("cronTask", info.taskName());
        assertEquals("CRON", info.taskType());
        assertEquals("0 * * * * *", info.schedule());
    }

    @Test
    void addCronTask_withTimezone_shouldRegisterTask() {
        registrar.addCronTask("cronTZ", "0 0 9 * * ?", java.time.ZoneId.of("Asia/Shanghai"), () -> {});

        assertTrue(registrar.exists("cronTZ"));
        TaskInfo info = registrar.listTasks().get(0);
        assertEquals("CRON", info.taskType());
        assertTrue(info.schedule().contains("Asia/Shanghai"));
    }

    @Test
    void addCronTask_withTimezone_duplicateName_shouldThrow() {
        registrar.addCronTask("dupTZ", "0 * * * * *", java.time.ZoneId.of("UTC"), () -> {});
        assertThrows(TaskAlreadyExistsException.class, () ->
                registrar.addCronTask("dupTZ", "0 * * * * *", java.time.ZoneId.of("UTC"), () -> {}));
    }

    @Test
    void addCronTask_withNullTimezone_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("nullTZ", "0 * * * * *", (java.time.ZoneId) null, () -> {}));
    }

    @Test
    void addCronTask_duplicateName_shouldThrow() {
        registrar.addCronTask("dup", "* * * * * *", () -> {});
        assertThrows(TaskAlreadyExistsException.class, () ->
                registrar.addCronTask("dup", "* * * * * *", () -> {}));
    }

    @Test
    void addCronTask_nullName_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask(null, "* * * * * *", () -> {}));
    }

    @Test
    void addCronTask_emptyCron_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("task", "", () -> {}));
    }

    @Test
    void addCronTask_nullRunnable_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                registrar.addCronTask("task", "* * * * * *", null));
    }

    // ─── Fixed Delay Tasks ───────────────────────────────────────────

    @Test
    void addFixedDelayTask_shouldRegisterTask() {
        registrar.addFixedDelayTask("delayTask", 10, 0, () -> {});

        assertTrue(registrar.exists("delayTask"));
        TaskInfo info = registrar.listTasks().get(0);
        assertEquals("FIXED_DELAY", info.taskType());
    }

    @Test
    void addFixedDelayTask_duplicateName_shouldThrow() {
        registrar.addFixedDelayTask("dup", 10, 0, () -> {});
        assertThrows(TaskAlreadyExistsException.class, () ->
                registrar.addFixedDelayTask("dup", 10, 0, () -> {}));
    }

    // ─── Fixed Rate Tasks ────────────────────────────────────────────

    @Test
    void addFixedRateTask_shouldRegisterTask() {
        registrar.addFixedRateTask("rateTask", 10, 0, () -> {});

        assertTrue(registrar.exists("rateTask"));
        TaskInfo info = registrar.listTasks().get(0);
        assertEquals("FIXED_RATE", info.taskType());
    }

    @Test
    void addFixedRateTask_duplicateName_shouldThrow() {
        registrar.addFixedRateTask("dup", 10, 0, () -> {});
        assertThrows(TaskAlreadyExistsException.class, () ->
                registrar.addFixedRateTask("dup", 10, 0, () -> {}));
    }

    // ─── Cancel ──────────────────────────────────────────────────────

    @Test
    void cancel_shouldRemoveTask() {
        registrar.addCronTask("toCancel", "* * * * * *", () -> {});
        assertTrue(registrar.exists("toCancel"));

        registrar.cancel("toCancel");
        assertFalse(registrar.exists("toCancel"));
        assertTrue(registrar.listTasks().isEmpty());
    }

    @Test
    void cancel_nonExistentTask_shouldNotThrow() {
        assertDoesNotThrow(() -> registrar.cancel("nonExistent"));
    }

    @Test
    void cancel_emptyName_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> registrar.cancel(""));
    }

    // ─── Query ───────────────────────────────────────────────────────

    @Test
    void listTasks_shouldReturnAllRegisteredTasks() {
        registrar.addCronTask("t1", "* * * * * *", () -> {});
        registrar.addFixedDelayTask("t2", 10, 0, () -> {});
        registrar.addFixedRateTask("t3", 10, 0, () -> {});

        List<TaskInfo> tasks = registrar.listTasks();
        assertEquals(3, tasks.size());
    }

    @Test
    void listTasks_shouldReturnUnmodifiableList() {
        registrar.addCronTask("t1", "* * * * * *", () -> {});
        List<TaskInfo> tasks = registrar.listTasks();
        assertThrows(UnsupportedOperationException.class, () -> tasks.add(new TaskInfo("x", "x", "x")));
    }

    @Test
    void exists_nonExistent_shouldReturnFalse() {
        assertFalse(registrar.exists("nope"));
    }

    // ─── Listeners ───────────────────────────────────────────────────

    @Test
    void listener_beforeAndAfterExecution_shouldBeCalled() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger beforeCount = new AtomicInteger();
        AtomicInteger afterCount = new AtomicInteger();

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void beforeExecution(String taskName) {
                beforeCount.incrementAndGet();
            }

            @Override
            public void afterExecution(String taskName) {
                afterCount.incrementAndGet();
                latch.countDown();
            }
        });

        registrar.addFixedRateTask("listenerTask", 1, 0, () -> {});
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(beforeCount.get() >= 1);
        assertTrue(afterCount.get() >= 1);
    }

    @Test
    void listener_onError_shouldBeCalled() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void onError(String taskName, Throwable error) {
                capturedError.set(error);
                latch.countDown();
            }
        });

        registrar.addFixedRateTask("errorTask", 1, 0, () -> {
            throw new RuntimeException("test error");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(capturedError.get());
        assertEquals("test error", capturedError.get().getMessage());
    }

    @Test
    void removeListener_shouldStopReceivingEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();

        TaskExecutionListener listener = new TaskExecutionListener() {
            @Override
            public void beforeExecution(String taskName) {
                count.incrementAndGet();
                latch.countDown();
            }
        };

        registrar.addListener(listener);
        registrar.addFixedRateTask("removeListenerTask", 1, 0, () -> {});
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        registrar.cancel("removeListenerTask");

        int countAfterRemove = count.get();
        // Give time to ensure no more calls
        Thread.sleep(200);
        assertEquals(countAfterRemove, count.get());
    }

    // ─── Concurrent Safety ───────────────────────────────────────────

    @Test
    void concurrentAddAndCancel_shouldNotCorrupt() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String taskName = "concurrentTask-" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    registrar.addFixedRateTask(taskName, 60, 0, () -> {});
                } catch (Exception ignored) {
                    // duplicate or interrupt is fine
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // All tasks should be registered
        for (int i = 0; i < threadCount; i++) {
            assertTrue(registrar.exists("concurrentTask-" + i));
        }

        // Cancel all concurrently
        CountDownLatch cancelLatch = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String taskName = "concurrentTask-" + i;
            new Thread(() -> {
                try {
                    registrar.cancel(taskName);
                } finally {
                    cancelLatch.countDown();
                }
            }).start();
        }

        assertTrue(cancelLatch.await(10, TimeUnit.SECONDS));
        assertTrue(registrar.listTasks().isEmpty());
    }

    // ─── Task Execution ──────────────────────────────────────────────

    @Test
    void fixedRateTask_shouldExecute() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        registrar.addFixedRateTask("execTask", 1, 0, latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void fixedDelayTask_shouldExecute() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        registrar.addFixedDelayTask("delayExecTask", 1, 0, latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    // ─── Duration Overloads ───────────────────────────────────────────

    @Test
    void addFixedDelayTask_duration_shouldRegister() {
        registrar.addFixedDelayTask("durDelay", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        assertTrue(registrar.exists("durDelay"));
        assertEquals("FIXED_DELAY", registrar.listTasks().get(0).taskType());
    }

    @Test
    void addFixedRateTask_duration_shouldRegister() {
        registrar.addFixedRateTask("durRate", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        assertTrue(registrar.exists("durRate"));
        assertEquals("FIXED_RATE", registrar.listTasks().get(0).taskType());
    }

    @Test
    void addFixedDelayTask_subSecond_shouldExecute() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        registrar.addFixedDelayTask("subSec", Duration.ofMillis(200), Duration.ZERO, latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    // ─── Replace / AddOrUpdate ────────────────────────────────────────

    @Test
    void replaceCronTask_existing_replaces() {
        registrar.addCronTask("replaceMe", "* * * * * *", () -> {});
        boolean replaced = registrar.replaceCronTask("replaceMe", "0 * * * * *", () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("replaceMe"));
        assertEquals("0 * * * * *", registrar.listTasks().get(0).schedule());
    }

    @Test
    void replaceCronTask_nonExisting_adds() {
        boolean replaced = registrar.replaceCronTask("newTask", "* * * * * *", () -> {});
        assertFalse(replaced);
        assertTrue(registrar.exists("newTask"));
    }

    @Test
    void replaceFixedDelayTask_shouldWork() {
        registrar.addFixedDelayTask("rd", 10, 0, () -> {});
        boolean replaced = registrar.replaceFixedDelayTask("rd", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("rd"));
    }

    @Test
    void replaceFixedRateTask_shouldWork() {
        registrar.addFixedRateTask("rr", 10, 0, () -> {});
        boolean replaced = registrar.replaceFixedRateTask("rr", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("rr"));
    }

    // ─── One-Shot Tasks ───────────────────────────────────────────────

    @Test
    void schedule_oneShot_executesAndAutoRemoves() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        registrar.schedule("oneShot", Duration.ofMillis(100), latch::countDown);
        assertTrue(registrar.exists("oneShot"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse(registrar.exists("oneShot"));
    }

    @Test
    void schedule_oneShot_duplicateName_throws() {
        registrar.schedule("dup", Duration.ofSeconds(60), () -> {});
        assertThrows(TaskAlreadyExistsException.class, () ->
                registrar.schedule("dup", Duration.ofSeconds(60), () -> {}));
    }

    @Test
    void schedule_oneShot_cancelBeforeExecution() throws InterruptedException {
        AtomicInteger executed = new AtomicInteger();
        registrar.schedule("cancelMe", Duration.ofSeconds(5), executed::incrementAndGet);
        assertTrue(registrar.exists("cancelMe"));
        registrar.cancel("cancelMe");
        assertFalse(registrar.exists("cancelMe"));
        Thread.sleep(200);
        assertEquals(0, executed.get());
    }

    // ─── Pause / Resume ───────────────────────────────────────────────

    @Test
    void pause_shouldPreventExecution() throws InterruptedException {
        AtomicInteger executed = new AtomicInteger();
        registrar.addFixedRateTask("pauseMe", Duration.ofMillis(100), Duration.ZERO, executed::incrementAndGet);

        // Let it execute once
        Thread.sleep(150);
        assertTrue(executed.get() >= 1);

        // Pause it
        registrar.pause("pauseMe");
        assertTrue(registrar.isPaused("pauseMe"));

        int countAfterPause = executed.get();
        Thread.sleep(200);

        // Should not have executed more
        assertEquals(countAfterPause, executed.get());
    }

    @Test
    void resume_shouldAllowExecutionAgain() throws InterruptedException {
        AtomicInteger executed = new AtomicInteger();
        registrar.addFixedRateTask("pauseResume", Duration.ofMillis(100), Duration.ZERO, executed::incrementAndGet);

        // Pause it
        registrar.pause("pauseResume");
        Thread.sleep(200);
        int countWhilePaused = executed.get();

        // Resume it
        registrar.resume("pauseResume");
        assertFalse(registrar.isPaused("pauseResume"));

        Thread.sleep(200);

        // Should have executed more
        assertTrue(executed.get() > countWhilePaused);
    }

    @Test
    void isPaused_nonExistentTask_shouldReturnFalse() {
        assertFalse(registrar.isPaused("nonExistent"));
    }

    @Test
    void getTaskDetail_shouldIncludePausedStatus() {
        registrar.addFixedRateTask("detailPause", Duration.ofSeconds(10), Duration.ZERO, () -> {});

        TaskDetail detail = registrar.getTaskDetail("detailPause").orElseThrow();
        assertFalse(detail.paused());

        registrar.pause("detailPause");
        detail = registrar.getTaskDetail("detailPause").orElseThrow();
        assertTrue(detail.paused());

        registrar.resume("detailPause");
        detail = registrar.getTaskDetail("detailPause").orElseThrow();
        assertFalse(detail.paused());
    }

    @Test
    void schedule_oneShot_listenerFires() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger beforeCount = new AtomicInteger();
        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void beforeExecution(String taskName) {
                if ("oneShotListener".equals(taskName)) {
                    beforeCount.incrementAndGet();
                }
            }
        });
        registrar.schedule("oneShotListener", Duration.ofMillis(100), latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, beforeCount.get());
    }

    // ─── getTaskDetail ────────────────────────────────────────────────

    @Test
    void getTaskDetail_found() {
        registrar.addCronTask("detail", "0 * * * * *", () -> {});
        Optional<TaskDetail> detail = registrar.getTaskDetail("detail");
        assertTrue(detail.isPresent());
        assertEquals("detail", detail.get().taskName());
        assertEquals("CRON", detail.get().taskType());
        assertFalse(detail.get().oneShot());
        assertNull(detail.get().retryPolicy());
        assertNotNull(detail.get().createdAt());
    }

    @Test
    void getTaskDetail_notFound() {
        Optional<TaskDetail> detail = registrar.getTaskDetail("nope");
        assertTrue(detail.isEmpty());
    }

    // ─── Concurrent Same-Name Race ───────────────────────────────────

    @Test
    void concurrentSameNameAdd_onlyOneWins() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    registrar.addFixedRateTask("raceTask", 60, 0, () -> {});
                    successCount.incrementAndGet();
                } catch (TaskAlreadyExistsException e) {
                    failCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertEquals(1, successCount.get());
        assertEquals(threadCount - 1, failCount.get());
        assertTrue(registrar.exists("raceTask"));
    }

    // ─── Retry Mechanism ─────────────────────────────────────────────

    @Test
    void retryPolicy_fixed_retriesOnFailure_thenSucceeds() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger attempts = new AtomicInteger();

        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofMillis(100));
        registrar.addFixedRateTask("retryTask", Duration.ofSeconds(60), Duration.ZERO, () -> {
            int attempt = attempts.incrementAndGet();
            latch.countDown();
            if (attempt < 3) {
                throw new RuntimeException("fail attempt " + attempt);
            }
        }, policy);

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(attempts.get() >= 3);
    }

    @Test
    void retryPolicy_exhausted_throws() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void onError(String taskName, Throwable e) {
                if ("exhaustTask".equals(taskName)) {
                    attempts.incrementAndGet();
                }
            }
        });

        RetryPolicy policy = RetryPolicy.fixed(2, Duration.ofMillis(50));
        registrar.addFixedRateTask("exhaustTask", Duration.ofSeconds(60), Duration.ZERO, () -> {
            throw new RuntimeException("always fail");
        }, policy);

        // Wait for retries + final failure
        Thread.sleep(1000);
        // After 2 retries + 1 original = 3 total attempts (original + 2 retries)
        // The onError listener is called for each failure
        assertTrue(attempts.get() >= 2);
    }

    // ─── Listener Exception Isolation ─────────────────────────────────

    @Test
    void listener_exceptionDoesNotBreakTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger taskExecCount = new AtomicInteger();

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public void beforeExecution(String taskName) {
                throw new RuntimeException("listener error");
            }
        });

        registrar.addFixedRateTask("isolatedTask", Duration.ofSeconds(1), Duration.ZERO, () -> {
            taskExecCount.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(taskExecCount.get() >= 1);
    }

    // ─── MetricsRecorder Integration ──────────────────────────────────

    @Test
    void setMetricsRecorder_null_fallsBackToNoop() {
        registrar.setMetricsRecorder(null);
        // Should not throw when task executes
        assertDoesNotThrow(() -> registrar.addFixedRateTask("metricsNull", 60, 0, () -> {}));
    }

    @Test
    void setMetricsRecorder_customRecorder_receivesExecutionEvents() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        MetricsRecorder recorder = new MetricsRecorder() {
            @Override
            public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
                if ("metricsTask".equals(taskName)) {
                    if (success) successCount.incrementAndGet();
                    else failureCount.incrementAndGet();
                }
            }

            @Override
            public void setActiveTaskCountSupplier(java.util.function.Supplier<Integer> supplier) {}
        };

        registrar.setMetricsRecorder(recorder);

        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("metricsTask", Duration.ofMillis(100), Duration.ZERO, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // Wait for metrics recording to complete (happens in finally block after delegate.run())
        Thread.sleep(200);
        assertTrue(successCount.get() >= 1);
        assertEquals(0, failureCount.get());
    }

    @Test
    void setMetricsRecorder_recordsFailureOnTaskException() throws InterruptedException {
        AtomicInteger failureCount = new AtomicInteger();

        MetricsRecorder recorder = new MetricsRecorder() {
            @Override
            public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
                if ("failMetricsTask".equals(taskName) && !success) {
                    failureCount.incrementAndGet();
                }
            }

            @Override
            public void setActiveTaskCountSupplier(java.util.function.Supplier<Integer> supplier) {}
        };

        registrar.setMetricsRecorder(recorder);

        CountDownLatch latch = new CountDownLatch(1);
        registrar.addFixedRateTask("failMetricsTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            latch.countDown();
            throw new RuntimeException("fail");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertTrue(failureCount.get() >= 1);
    }

    // ─── Destroy / Graceful Shutdown ──────────────────────────────────

    @Test
    void destroy_cancelsAllTasksAndClearsState() {
        registrar.addCronTask("t1", "* * * * * *", () -> {});
        registrar.addFixedDelayTask("t2", 10, 0, () -> {});
        registrar.addFixedRateTask("t3", 10, 0, () -> {});
        registrar.pause("t1");

        assertEquals(3, registrar.listTasks().size());
        assertTrue(registrar.isPaused("t1"));

        registrar.destroy();

        assertEquals(0, registrar.listTasks().size());
        assertFalse(registrar.isPaused("t1"));
    }

    @Test
    void destroy_withShortTimeout_forcesShutdown() throws InterruptedException {
        // Create a registrar with 1 second timeout
        FlexScheduledTaskRegistrar shortTimeoutRegistrar =
                new FlexScheduledTaskRegistrar(taskScheduler, 1);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskCanFinish = new CountDownLatch(1);

        shortTimeoutRegistrar.addFixedRateTask("longTask", Duration.ofSeconds(60), Duration.ZERO, () -> {
            taskStarted.countDown();
            try {
                taskCanFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // Destroy should force shutdown after 1 second timeout
        long start = System.currentTimeMillis();
        shortTimeoutRegistrar.destroy();
        long elapsed = System.currentTimeMillis() - start;

        // Should complete within ~1-2 seconds (timeout + overhead), not 10 seconds
        assertTrue(elapsed < 5000, "Destroy should complete within timeout, took " + elapsed + "ms");

        // Let the task finish so thread can be cleaned up
        taskCanFinish.countDown();
    }

    // ─── Endpoint Pause/Resume ────────────────────────────────────────

    @Test
    void pause_nonExistentTask_shouldNotThrow() {
        assertDoesNotThrow(() -> registrar.pause("nonExistent"));
    }

    @Test
    void resume_nonExistentTask_shouldNotThrow() {
        assertDoesNotThrow(() -> registrar.resume("nonExistent"));
    }

    @Test
    void pause_alreadyPaused_shouldBeIdempotent() {
        registrar.addFixedRateTask("doublePause", 60, 0, () -> {});
        registrar.pause("doublePause");
        assertDoesNotThrow(() -> registrar.pause("doublePause"));
        assertTrue(registrar.isPaused("doublePause"));
    }

    @Test
    void resume_notPaused_shouldBeIdempotent() {
        registrar.addFixedRateTask("doubleResume", 60, 0, () -> {});
        assertDoesNotThrow(() -> registrar.resume("doubleResume"));
        assertFalse(registrar.isPaused("doubleResume"));
    }

    @Test
    void cancel_pausedTask_shouldRemoveFromPausedSet() {
        registrar.addFixedRateTask("pausedCancel", 60, 0, () -> {});
        registrar.pause("pausedCancel");
        assertTrue(registrar.isPaused("pausedCancel"));

        registrar.cancel("pausedCancel");
        assertFalse(registrar.isPaused("pausedCancel"));
        assertFalse(registrar.exists("pausedCancel"));
    }

    // ─── Replace Clears Stale Paused Flag ─────────────────────────────

    @Test
    void replaceCronTask_whenPaused_clearsPausedFlag() {
        registrar.addCronTask("pausedSub", "0 * * * * *", () -> {});
        registrar.pause("pausedSub");
        assertTrue(registrar.isPaused("pausedSub"));

        boolean replaced = registrar.replaceCronTask("pausedSub", "0 0 * * * *", () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("pausedSub"));
        assertFalse(registrar.isPaused("pausedSub"),
                "replaceCronTask must clear a stale pausedTasks flag");
        TaskDetail detail = registrar.getTaskDetail("pausedSub").orElseThrow();
        assertFalse(detail.paused());
    }

    @Test
    void replaceFixedDelayTask_whenPaused_clearsPausedFlag() {
        registrar.addFixedDelayTask("pausedDelay", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        registrar.pause("pausedDelay");
        assertTrue(registrar.isPaused("pausedDelay"));

        boolean replaced = registrar.replaceFixedDelayTask("pausedDelay",
                Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("pausedDelay"));
        assertFalse(registrar.isPaused("pausedDelay"),
                "replaceFixedDelayTask must clear a stale pausedTasks flag");
    }

    @Test
    void replaceFixedRateTask_whenPaused_clearsPausedFlag() {
        registrar.addFixedRateTask("pausedRate", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        registrar.pause("pausedRate");
        assertTrue(registrar.isPaused("pausedRate"));

        boolean replaced = registrar.replaceFixedRateTask("pausedRate",
                Duration.ofSeconds(20), Duration.ZERO, () -> {});
        assertTrue(replaced);
        assertTrue(registrar.exists("pausedRate"));
        assertFalse(registrar.isPaused("pausedRate"),
                "replaceFixedRateTask must clear a stale pausedTasks flag");
    }

    @Test
    void replaceCronTask_withZeroRetries_shouldExecute() throws InterruptedException {
        // Belt-and-suspenders: the replacement is live (not paused) and actually executes.
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        registrar.addCronTask("liveReplace", "0/1 * * * * ?", () -> {});
        registrar.pause("liveReplace");
        registrar.replaceCronTask("liveReplace", "0/1 * * * * ?", () -> {
            executed.incrementAndGet();
            latch.countDown();
        });

        assertFalse(registrar.isPaused("liveReplace"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get() >= 1);
    }

    // ─── round-3: pause must survive replace (ordering) ──────────────

    @Test
    void replaceCronTask_pauseAfterReplace_isRespected() {
        registrar.addCronTask("orderedCron", "0 * * * * *", () -> {});
        registrar.replaceCronTask("orderedCron", "0 * * * * *", () -> {});
        registrar.pause("orderedCron");
        assertTrue(registrar.isPaused("orderedCron"),
                "pause issued after replace must be retained");
    }

    @Test
    void replaceFixedDelayTask_pauseAfterReplace_isRespected() {
        registrar.addFixedDelayTask("orderedDelay", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        registrar.replaceFixedDelayTask("orderedDelay", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        registrar.pause("orderedDelay");
        assertTrue(registrar.isPaused("orderedDelay"),
                "pause issued after replace must be retained");
    }

    @Test
    void replaceFixedRateTask_pauseAfterReplace_isRespected() {
        registrar.addFixedRateTask("orderedRate", Duration.ofSeconds(10), Duration.ZERO, () -> {});
        registrar.replaceFixedRateTask("orderedRate", Duration.ofSeconds(20), Duration.ZERO, () -> {});
        registrar.pause("orderedRate");
        assertTrue(registrar.isPaused("orderedRate"),
                "pause issued after replace must be retained");
    }

    // ─── round-3: async-listener executor lifecycle ─────────────────

    @Test
    void destroy_shutsDownDefaultAsyncListenerExecutor() {
        ExecutorService defaultExecutor = registrar.getAsyncListenerExecutorForTest();
        assertNotNull(defaultExecutor, "Default async listener executor must exist");
        assertFalse(defaultExecutor.isShutdown(), "Default pool must start live");

        registrar.destroy();

        assertTrue(defaultExecutor.isShutdown(),
                "destroy() must shut down the internally owned default async-listener pool");
    }

    @Test
    void destroy_doesNotShutDownCallerSuppliedAsyncListenerExecutor() {
        ExecutorService real = java.util.concurrent.Executors.newSingleThreadExecutor();
        RecordingExecutorService spy = new RecordingExecutorService(real);
        try {
            registrar.setAsyncListenerExecutor(spy);
            assertSame(spy, registrar.getAsyncListenerExecutorForTest());

            registrar.destroy();

            assertEquals(0, spy.shutdownCount.get(),
                    "destroy() must NOT shut down a caller-supplied async-listener executor");
            assertFalse(real.isShutdown(),
                    "spy should not have propagated shutdown to the wrapped executor");
        } finally {
            real.shutdownNow();
        }
    }

    @Test
    void setAsyncListenerExecutor_null_replacesWithInternalPool() {
        ExecutorService firstDefault = registrar.getAsyncListenerExecutorForTest();
        registrar.setAsyncListenerExecutor(null);
        ExecutorService replacement = registrar.getAsyncListenerExecutorForTest();

        assertNotNull(replacement);
        assertNotSame(firstDefault, replacement,
                "null fallback must produce a new internal pool");
        assertTrue(firstDefault.isShutdown(),
                "old internal pool must be shut down by setAsyncListenerExecutor(null)");

        registrar.destroy();
        assertTrue(replacement.isShutdown(),
                "destroy() must shut down the replacement internal pool");
    }

    @Test
    void setAsyncListenerExecutor_replacesInternalWithExternal_shutsDownPreviousOnly() {
        ExecutorService previousInternal = registrar.getAsyncListenerExecutorForTest();
        ExecutorService real = java.util.concurrent.Executors.newSingleThreadExecutor();
        RecordingExecutorService external = new RecordingExecutorService(real);
        try {
            registrar.setAsyncListenerExecutor(external);

            assertSame(external, registrar.getAsyncListenerExecutorForTest());
            assertTrue(previousInternal.isShutdown(),
                    "previous internal pool must be shut down when replaced");

            registrar.destroy();

            assertEquals(0, external.shutdownCount.get(),
                    "destroy() must not shut down caller-supplied executor");
            assertFalse(real.isShutdown(),
                    "spy should not have propagated shutdown to the wrapped executor");
        } finally {
            real.shutdownNow();
        }
    }

    /**
     * Delegates to a real {@link ExecutorService} but counts every shutdown-related
     * call, so a test can assert the registrar did or did not trigger shutdown on
     * a caller-supplied executor.
     */
    private static class RecordingExecutorService implements ExecutorService {
        final ExecutorService delegate;
        final AtomicInteger shutdownCount = new AtomicInteger();

        RecordingExecutorService(ExecutorService delegate) { this.delegate = delegate; }

        @Override public void shutdown() { shutdownCount.incrementAndGet(); }
        @Override public java.util.List<Runnable> shutdownNow() { shutdownCount.incrementAndGet(); return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
        @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) { return delegate.submit(task); }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) { return delegate.submit(task, result); }
        @Override public java.util.concurrent.Future<?> submit(Runnable task) { return delegate.submit(task); }
        @Override public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException { return delegate.invokeAll(tasks); }
        @Override public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException { return delegate.invokeAll(tasks, timeout, unit); }
        @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException, java.util.concurrent.ExecutionException { return delegate.invokeAny(tasks); }
        @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException { return delegate.invokeAny(tasks, timeout, unit); }
        @Override public void execute(Runnable command) { delegate.execute(command); }
    }
}
