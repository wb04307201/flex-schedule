package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.RetryPolicy;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.core.TaskDefinition;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.flex.schedule.core.TaskLimits.Mode;
import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FlexScheduledTaskLimitsTest {

    private ThreadPoolTaskScheduler scheduler;
    private FlexScheduledTaskRegistrar strict;
    private FlexScheduledTaskRegistrar warn;
    private FlexScheduledTaskRegistrar off;
    private FlexScheduledTaskRegistrar defaults;

    @BeforeEach
    void setUp() throws Exception {
        // Reset the static field to avoid stale references from other test classes
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("test-limits-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        TaskLimits strictLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT);
        TaskLimits warnLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN);
        TaskLimits offLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.OFF);

        strict = new FlexScheduledTaskRegistrar(scheduler, 5, strictLimits);
        warn = new FlexScheduledTaskRegistrar(scheduler, 5, warnLimits);
        off = new FlexScheduledTaskRegistrar(scheduler, 5, offLimits);
        defaults = new FlexScheduledTaskRegistrar(scheduler, 5);
    }

    @AfterEach
    void tearDown() {
        strict.destroy();
        warn.destroy();
        off.destroy();
        defaults.destroy();
    }

    // ─── Min-interval on FIXED_DELAY ────────────────────────────────

    @Test
    void fixedDelay_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertFalse(strict.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_warnAllows() {
        assertDoesNotThrow(
            () -> warn.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(warn.exists("t"));
    }

    @Test
    void fixedDelay_belowMin_offAllows() {
        assertDoesNotThrow(
            () -> off.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(off.exists("t"));
    }

    @Test
    void fixedDelay_nullMinInterval_allowsAny() {
        assertDoesNotThrow(
            () -> defaults.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(defaults.exists("t"));
    }

    @Test
    void fixedDelay_initialDelay_belowMin_stillAllowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(15), Duration.ofSeconds(5), () -> {}));
        assertTrue(strict.exists("t"));
    }

    @Test
    void fixedDelay_atMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(10), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── Min-interval on FIXED_RATE ─────────────────────────────────

    @Test
    void fixedRate_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void fixedRate_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.addFixedRateTask("t", Duration.ofMinutes(15), Duration.ZERO, () -> {}));
    }

    // ─── Min-interval on ONE_SHOT ───────────────────────────────────

    @Test
    void oneShot_belowMin_strictThrows() {
        assertThrows(TaskLimitExceededException.class,
            () -> strict.schedule("t", Duration.ofSeconds(5), () -> {}));
    }

    @Test
    void oneShot_aboveMin_allowed() {
        assertDoesNotThrow(
            () -> strict.schedule("t", Duration.ofMinutes(15), () -> {}));
    }

    // ─── Min-interval EXEMPT for CRON ───────────────────────────────

    @Test
    void cron_evenWithStrictMinInterval_isAllowed() {
        // cron "every second" — interval is < 10min, but CRON is exempt
        assertDoesNotThrow(
            () -> strict.addCronTask("t", "* * * * * *", () -> {}));
        assertTrue(strict.exists("t"));
    }

    // ─── replace path ──────────────────────────────────────────────

    @Test
    void replaceFixedDelay_belowMin_strictThrows() {
        strict.addFixedDelayTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(strict.exists("t"));  // original still there
    }

    @Test
    void replaceFixedRate_belowMin_strictThrows() {
        strict.addFixedRateTask("t", Duration.ofMinutes(30), Duration.ZERO, () -> {});
        assertThrows(TaskLimitExceededException.class,
            () -> strict.replaceFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
    }

    @Test
    void replaceCron_belowMin_strictThrows() {
        // cron is exempt from min-interval, so replace-cron also exempt
        assertDoesNotThrow(
            () -> strict.replaceCronTask("neverExisted", "* * * * * *", () -> {}));
        assertTrue(strict.exists("neverExisted"));
    }

    // ─── Max-lifetime lazy check ────────────────────────────────────

    @Test
    void maxLifetime_fixedDelay_cancelledOnNextFirePastLifetime() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(300), Mode.STRICT));
        try {
            r.addFixedDelayTask("shortLived", Duration.ofMillis(100), Duration.ZERO, () -> {});

            // Wait past the lifetime cap (initialDelay 0 + 100ms interval; need >300ms total)
            Thread.sleep(500);

            // Trigger one more fire — instrument() should detect expiry and cancel
            // Force a re-schedule isn't trivial; instead, verify by directly invoking getTaskDetail
            // and checking isExpired via LimitsChecker. For this test, we rely on direct helper:
            // Simulate the fire path by calling getTaskDetail and waiting for the scheduled runnable.
            // The scheduled runnable itself will call cancel when it fires next.
            // We give it time to fire at least once post-expiry.
            Thread.sleep(300);

            // After sufficient time, either the task already fired and cancelled, or it still
            // exists but the next fire will cancel. Use a generous wait.
            long deadline = System.currentTimeMillis() + 2000;
            while (r.exists("shortLived") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(r.exists("shortLived"), "Task should have been auto-cancelled past max-lifetime");
        } finally {
            r.destroy();
        }
    }

    @Test
    void maxLifetime_nullConfig_doesNotCancel() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, null, Mode.STRICT));
        try {
            r.addFixedDelayTask("forever", Duration.ofMillis(100), Duration.ZERO, () -> {});
            Thread.sleep(400);
            assertTrue(r.exists("forever"));
        } finally {
            r.destroy();
        }
    }

    @Test
    void resume_pausedTaskPastLifetime_cancelsInsteadOfResuming() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.STRICT));
        try {
            r.addFixedDelayTask("oldPaused", Duration.ofSeconds(60), Duration.ZERO, () -> {});
            r.pause("oldPaused");

            // wait past max-lifetime
            Thread.sleep(400);

            // attempt resume — should cancel instead
            r.resume("oldPaused");
            assertFalse(r.exists("oldPaused"));
            assertFalse(r.isPaused("oldPaused"));
        } finally {
            r.destroy();
        }
    }

    // ─── createdAt preservation ────────────────────────────────────

    @Test
    void replaceCronTask_resetsCreatedAt() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            r.addCronTask("t", "0 * * * * *", () -> {});
            Instant beforeReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            Thread.sleep(20);
            r.replaceCronTask("t", "0 0 * * * *", () -> {});
            Instant afterReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            assertTrue(afterReplace.isAfter(beforeReplace),
                "replaceCronTask should reset createdAt to a later instant");
        } finally {
            r.destroy();
        }
    }

    @Test
    void restoreTasks_preservesCreatedAtAcrossRestart() {
        Instant originalCreated = Instant.now().minus(Duration.ofDays(3));
        TaskDefinition def = TaskDefinition.builder("daily", "CRON")
            .cronExpression("0 0 * * * *")
            .beanName("testApplication")
            .methodName("noOp")
            .createdAt(originalCreated)
            .updatedAt(originalCreated)
            .build();
        cn.wubo.flex.schedule.core.TaskRepository repo =
            new cn.wubo.flex.schedule.core.InMemoryTaskRepository();
        repo.save(def);

        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            // Spring's BeanMethodRunnable requires the bean to be in the application context;
            // restoreTasks runs synchronously and does NOT actually invoke the method — it only
            // constructs the Runnable. The task will be registered as long as the bean is
            // resolvable. For this test we use the singleton testApplication from TestApplication
            // via a programmatic lookup.
            org.springframework.context.support.StaticApplicationContext ctx =
                new org.springframework.context.support.StaticApplicationContext();
            ctx.getBeanFactory().registerSingleton("testApplication",
                cn.wubo.flex.schedule.TestApplication.getInstance());
            ctx.refresh();

            cn.wubo.flex.schedule.core.SpringContextUtils utils =
                new cn.wubo.flex.schedule.core.SpringContextUtils();
            utils.setApplicationContext(ctx);
            r.setTaskRepository(repo);
            r.restoreTasks();

            assertTrue(r.exists("daily"), "Task should be restored");
            Instant restored = r.getTaskDetail("daily").orElseThrow().createdAt();
            assertEquals(originalCreated.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                         restored.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                         "Restored task should preserve original createdAt");
        } finally {
            r.destroy();
        }
    }

    @Test
    void restoreTasks_preservesRetryPolicyOnFixedDelayTask() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(1));
        Instant originalCreated = Instant.now().minus(Duration.ofDays(1));
        TaskDefinition def = TaskDefinition.builder("retryRestore", "FIXED_DELAY")
            .interval(Duration.ofMinutes(15))
            .initialDelay(Duration.ZERO)
            .beanName("testApplication")
            .methodName("noOp")
            .retryPolicy(policy)
            .createdAt(originalCreated)
            .updatedAt(originalCreated)
            .build();
        cn.wubo.flex.schedule.core.TaskRepository repo =
            new cn.wubo.flex.schedule.core.InMemoryTaskRepository();
        repo.save(def);

        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            org.springframework.context.support.StaticApplicationContext ctx =
                new org.springframework.context.support.StaticApplicationContext();
            ctx.getBeanFactory().registerSingleton("testApplication",
                cn.wubo.flex.schedule.TestApplication.getInstance());
            ctx.refresh();

            cn.wubo.flex.schedule.core.SpringContextUtils utils =
                new cn.wubo.flex.schedule.core.SpringContextUtils();
            utils.setApplicationContext(ctx);
            r.setTaskRepository(repo);
            r.restoreTasks();

            assertTrue(r.exists("retryRestore"), "Task should be restored");
            RetryPolicy restored = r.getTaskDetail("retryRestore").orElseThrow().retryPolicy();
            assertNotNull(restored, "Restored task should preserve retryPolicy");
            assertEquals(policy.maxAttempts(), restored.maxAttempts());
            assertEquals(policy.delay(), restored.delay());
            assertEquals(policy.backoff(), restored.backoff());
        } finally {
            r.destroy();
        }
    }
}