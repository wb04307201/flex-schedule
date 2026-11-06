package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.InMemoryExecutionHistory;
import cn.wubo.flex.schedule.core.TaskExecutionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Async task-execution-listener tests for {@link FlexScheduledTaskRegistrar}
 * (1.3.0 test-completion plan, scenarios A4/A5/A20).
 * <p>
 * A4 pins that listeners with {@code isAsync() == true} receive
 * before/after/onError dispatches on the registrar's async-listener executor
 * threads, never on the scheduler thread. A5 pins that a shut-down
 * async-listener executor is survivable: the {@code RejectedExecutionException}
 * is absorbed, the task still runs, and the execution history still shows a
 * single success record (no spurious failure record appended). A20 pins the
 * null-guard on {@code addListener}.
 * </p>
 * <p>
 * Timing discipline: one-shot delays >= 100ms; all assertions wait on latches
 * or bounded polling, never on a fixed sleep followed by an exact check.
 * </p>
 */
class RegistrarAsyncListenerTest {

    /** Thread-name pattern used by the registrar's internally-owned async listener pool. */
    private static final String ASYNC_THREAD_PREFIX = "async-listener";
    private static final String SCHEDULER_PREFIX = "a4-sched-";

    private ThreadPoolTaskScheduler taskScheduler;
    private FlexScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix(SCHEDULER_PREFIX);
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(taskScheduler, 5);
        registrar.setExecutionHistory(new InMemoryExecutionHistory(10));
    }

    @AfterEach
    void tearDown() {
        registrar.destroy();
    }

    // ─── A4 ──────────────────────────────────────────────────────────

    @Test
    void asyncListener_receivesEventsOnExecutorThread() throws Exception {
        // phase keys: "before:<task>", "after:<task>", "error:<task>"
        Map<String, String> phaseThreads = new ConcurrentHashMap<>();
        AtomicReference<String> okTaskThread = new AtomicReference<>();
        AtomicReference<String> failTaskThread = new AtomicReference<>();
        AtomicReference<Throwable> seenError = new AtomicReference<>();

        CountDownLatch beforeOk = new CountDownLatch(1);
        CountDownLatch afterOk = new CountDownLatch(1);
        CountDownLatch beforeFail = new CountDownLatch(1);
        CountDownLatch errorFail = new CountDownLatch(1);
        CountDownLatch okTaskStarted = new CountDownLatch(1);
        CountDownLatch failTaskStarted = new CountDownLatch(1);

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public boolean isAsync() {
                return true;
            }

            @Override
            public void beforeExecution(String taskName) {
                phaseThreads.put("before:" + taskName, Thread.currentThread().getName());
                if ("a4-ok".equals(taskName)) {
                    beforeOk.countDown();
                } else {
                    beforeFail.countDown();
                }
            }

            @Override
            public void afterExecution(String taskName) {
                phaseThreads.put("after:" + taskName, Thread.currentThread().getName());
                afterOk.countDown();
            }

            @Override
            public void onError(String taskName, Throwable error) {
                phaseThreads.put("error:" + taskName, Thread.currentThread().getName());
                seenError.set(error);
                errorFail.countDown();
            }
        });

        // One successful and one failing one-shot task, >=100ms apart from registration.
        registrar.schedule("a4-ok", Duration.ofMillis(100), () -> {
            okTaskThread.set(Thread.currentThread().getName());
            okTaskStarted.countDown();
        });
        registrar.schedule("a4-boom", Duration.ofMillis(150), () -> {
            failTaskThread.set(Thread.currentThread().getName());
            failTaskStarted.countDown();
            throw new RuntimeException("a4-boom");
        });

        assertTrue(okTaskStarted.await(5, TimeUnit.SECONDS), "success task must run");
        assertTrue(failTaskStarted.await(5, TimeUnit.SECONDS), "failing task must run");
        assertTrue(beforeOk.await(5, TimeUnit.SECONDS), "async beforeExecution(success) must arrive");
        assertTrue(afterOk.await(5, TimeUnit.SECONDS), "async afterExecution(success) must arrive");
        assertTrue(beforeFail.await(5, TimeUnit.SECONDS), "async beforeExecution(failure) must arrive");
        assertTrue(errorFail.await(5, TimeUnit.SECONDS), "async onError(failure) must arrive");

        assertNotNull(seenError.get(), "onError must receive the failure");
        assertEquals("a4-boom", seenError.get().getMessage());

        // All four lifecycle phases recorded, each on the async executor's threads.
        for (String key : List.of("before:a4-ok", "after:a4-ok", "before:a4-boom", "error:a4-boom")) {
            String listenerThread = phaseThreads.get(key);
            assertNotNull(listenerThread, key + " must have been delivered to the async listener");
            assertTrue(listenerThread.startsWith(ASYNC_THREAD_PREFIX),
                    key + " must run on the async listener executor (threads named '"
                            + ASYNC_THREAD_PREFIX + "'), but ran on: " + listenerThread);
            assertNotEquals(okTaskThread.get(), listenerThread,
                    key + " must NOT run on the scheduler thread that executed the task");
            assertNotEquals(failTaskThread.get(), listenerThread,
                    key + " must NOT run on the scheduler thread that executed the task");
        }

        // Sanity: the tasks themselves ran on scheduler threads, not async-listener threads.
        assertTrue(okTaskThread.get().startsWith(SCHEDULER_PREFIX),
                "task delegate must run on a scheduler thread, got " + okTaskThread.get());
        assertTrue(failTaskThread.get().startsWith(SCHEDULER_PREFIX),
                "task delegate must run on a scheduler thread, got " + failTaskThread.get());
    }

    // ─── A5 ──────────────────────────────────────────────────────────

    @Test
    void asyncListener_executorShutdown_taskStillSucceeds() throws Exception {
        AtomicInteger listenerCalls = new AtomicInteger();
        CountDownLatch taskRan = new CountDownLatch(1);

        // Caller-supplied async-listener executor, shut down BEFORE the task fires:
        // every submitAsync for this task must be rejected and absorbed.
        ExecutorService dead = Executors.newSingleThreadExecutor();
        registrar.setAsyncListenerExecutor(dead);
        dead.shutdown();

        registrar.addListener(new TaskExecutionListener() {
            @Override
            public boolean isAsync() {
                return true;
            }

            @Override
            public void beforeExecution(String taskName) {
                listenerCalls.incrementAndGet();
            }

            @Override
            public void afterExecution(String taskName) {
                listenerCalls.incrementAndGet();
            }

            @Override
            public void onError(String taskName, Throwable error) {
                listenerCalls.incrementAndGet();
            }
        });

        registrar.schedule("a5-task", Duration.ofMillis(200), taskRan::countDown);

        assertTrue(taskRan.await(5, TimeUnit.SECONDS),
                "the task must still execute even though async listener dispatch is rejected "
                        + "(an escaping RejectedExecutionException from beforeExecution would skip the delegate)");

        // Settle window: if the afterExecution RejectedExecutionException escaped,
        // instrument's catch block would record a SECOND (failed) history record.
        Thread.sleep(300);

        List<ExecutionRecord> records = registrar.getExecutionHistory().getHistory("a5-task", 10);
        assertEquals(1, records.size(),
                "exactly one history record expected; a leaked RejectedExecutionException "
                        + "would append a spurious failure record, got: " + records);
        assertTrue(records.get(0).success(),
                "the single record must be a success, got: " + records.get(0));
        assertNull(records.get(0).error());
        assertEquals(0, listenerCalls.get(),
                "no async listener callback can run on a shut-down executor");
    }

    // ─── A20 ─────────────────────────────────────────────────────────

    @Test
    void addListener_null_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registrar.addListener(null));
        assertTrue(ex.getMessage().contains("listener"),
                "message should name the offending argument, got: " + ex.getMessage());
    }
}
