package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.InMemoryTaskRepository;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskDefinition;
import cn.wubo.flex.schedule.core.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defensive-branch tests for {@link FlexScheduledTaskRegistrar#restoreTasks()} (Task 5,
 * scenario A8). Every malformed / conflicting persisted definition must be skipped with a
 * log entry — restoreTasks must never throw out of the loop and must never corrupt the
 * state of already-registered tasks.
 * <p>
 * SpringContextUtils holds STATIC state; this class follows the disclosed reflection-reset
 * pattern from {@code FlexScheduledTaskLimitsTest}: capture the previous value in
 * {@code @BeforeEach}, null it before installing the test context, and restore the captured
 * value in {@code @AfterEach} so the suite neither depends on nor pollutes other classes.
 * </p>
 */
class RegistrarRestoreTasksEdgeTest {

    /** Counting bean invoked via BeanMethodRunnable when a restored task actually fires. */
    public static class CountingBean {
        final AtomicInteger count = new AtomicInteger();

        public void tick() {
            count.incrementAndGet();
        }
    }

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar registrar;
    private TaskRepository repo;
    private CountingBean countingBean;
    private StaticApplicationContext context;

    private Object previousStaticContext;

    @BeforeEach
    void setUp() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        previousStaticContext = field.get(null);
        field.set(null, null);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-restore-edge-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar = new FlexScheduledTaskRegistrar(scheduler, 5);
        repo = new InMemoryTaskRepository();
        registrar.setTaskRepository(repo);

        context = new StaticApplicationContext();
        countingBean = new CountingBean();
        context.getBeanFactory().registerSingleton("countingBean", countingBean);
        context.refresh();
        new SpringContextUtils().setApplicationContext(context);
    }

    @AfterEach
    void tearDown() throws Exception {
        registrar.destroy();
        context.close();
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, previousStaticContext);
    }

    // ─── 1. definition without beanName/methodName: skipped, no throw ──

    @Test
    void restoreTasks_definitionWithoutBeanMethod_skippedWithoutThrow() {
        TaskDefinition def = TaskDefinition.builder("noBeanTask", "CRON")
                .cronExpression("0 0 * * * *")
                .build(); // beanName / methodName left null
        repo.save(def);

        assertDoesNotThrow(registrar::restoreTasks);
        assertFalse(registrar.exists("noBeanTask"),
                "definition without bean/method info must be skipped, not registered");
    }

    // ─── 2. name collision with live task: skipped, old task untouched ─

    @Test
    void restoreTasks_duplicateName_skippedAndOldTaskPreserved() {
        // Live task with a schedule that differs from the persisted definition.
        registrar.addCronTask("dupTask", "0 0 * * * *", () -> { /* no-op */ });

        TaskDefinition def = TaskDefinition.builder("dupTask", "FIXED_RATE")
                .interval(Duration.ofMinutes(10))
                .initialDelay(Duration.ZERO)
                .beanName("countingBean")
                .methodName("tick")
                .build();
        repo.save(def);

        assertDoesNotThrow(registrar::restoreTasks);
        assertTrue(registrar.exists("dupTask"));
        // The restore was skipped: the live entry keeps the ORIGINAL cron schedule,
        // not the persisted definition's FIXED_RATE descriptor.
        assertEquals("CRON", registrar.getTaskDetail("dupTask").orElseThrow().taskType());
        assertEquals("0 0 * * * *", registrar.getTaskDetail("dupTask").orElseThrow().schedule());
        assertEquals(0, countingBean.count.get(), "skipped restore must not execute anything");
    }

    // ─── 3. unknown taskType: IllegalArgumentException internally, swallowed defensively ──

    @Test
    void restoreTasks_unknownTaskType_skippedWithoutThrow() {
        TaskDefinition def = TaskDefinition.builder("weirdTask", "BOGUS_TYPE")
                .beanName("countingBean")
                .methodName("tick")
                .interval(Duration.ofMinutes(10))
                .initialDelay(Duration.ZERO)
                .build();
        repo.save(def);

        // restoreTasks catches and logs per-definition failures: no exception escapes
        // and the placeholder inserted before scheduling is rolled back.
        assertDoesNotThrow(registrar::restoreTasks);
        assertFalse(registrar.exists("weirdTask"),
                "unknown taskType must leave no entry in the task map");

        // Pin the exception TYPE the defensive branch absorbs: scheduleByType itself
        // throws IllegalArgumentException for an unknown task type.
        assertThrows(IllegalArgumentException.class, () -> {
            Method scheduleByType = FlexScheduledTaskRegistrar.class
                    .getDeclaredMethod("scheduleByType", TaskDefinition.class, Runnable.class);
            scheduleByType.setAccessible(true);
            try {
                scheduleByType.invoke(registrar, def, (Runnable) () -> { });
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    // ─── 4. paused=true: restored paused and never executed ────────────

    @Test
    void restoreTasks_pausedDefinition_staysPausedAndNeverExecutes() throws InterruptedException {
        Instant updatedAt = Instant.now().minusSeconds(30);
        TaskDefinition def = TaskDefinition.builder("pausedRestore", "FIXED_RATE")
                .interval(Duration.ofMillis(100))     // would fire repeatedly if live
                .initialDelay(Duration.ZERO)
                .beanName("countingBean")
                .methodName("tick")
                .paused(true)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
        repo.save(def);

        registrar.restoreTasks();

        assertTrue(registrar.exists("pausedRestore"), "paused definition must still be restored");
        assertTrue(registrar.isPaused("pausedRestore"),
                "restored paused=true definition must stay paused until resumed");
        assertTrue(registrar.getTaskDetail("pausedRestore").orElseThrow().paused());

        // No resume() is issued, so pause-gap catch-up must NOT run either:
        // across several would-be 100ms fires the delegate is never invoked.
        Thread.sleep(500);
        assertEquals(0, countingBean.count.get(),
                "restored-paused task must not execute while nobody resumes it");
    }
}
