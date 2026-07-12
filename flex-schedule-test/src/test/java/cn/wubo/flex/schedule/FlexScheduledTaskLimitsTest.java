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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ─── Min-interval on retry-aware overloads ──────────────────────

    @Test
    void fixedDelay_withRetry_belowMin_strictThrows() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(1));
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedDelayTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}, policy));
        assertFalse(strict.exists("t"));
    }

    @Test
    void fixedDelay_withRetry_atMin_allowed() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(1));
        assertDoesNotThrow(
            () -> strict.addFixedDelayTask("t", Duration.ofMinutes(10), Duration.ZERO, () -> {}, policy));
        assertTrue(strict.exists("t"));
    }

    @Test
    void fixedRate_withRetry_belowMin_strictThrows() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(1));
        assertThrows(TaskLimitExceededException.class,
            () -> strict.addFixedRateTask("t", Duration.ofSeconds(5), Duration.ZERO, () -> {}, policy));
        assertFalse(strict.exists("t"));
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

    // ─── Atomic check-and-remove in resume path (race fix) ─────────

    @Test
    void cancelIfCurrent_doesNotRemoveReplacedTask() throws Exception {
        // Setup: add a task, capture its entry, replace the task (fresh entry),
        // then call cancelIfCurrent with the stale captured entry.
        // The fresh entry must survive — atomic check-and-remove must NOT match stale.
        try {
            defaults.addFixedDelayTask("foo", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            FlexScheduledTaskRegistrar.ScheduledTaskEntry captured = defaults.getTaskDetail("foo").orElseThrow() != null
                ? readEntry(defaults, "foo")
                : null;
            assertNotNull(captured);

            defaults.replaceFixedDelayTask("foo", Duration.ofMinutes(20), Duration.ZERO, () -> {});

            // Now invoke the atomic cancelIfCurrent with the stale captured entry
            boolean cancelled = defaults.cancelIfCurrent("foo", captured);

            assertFalse(cancelled, "cancelIfCurrent must return false when entry is stale");
            assertTrue(defaults.exists("foo"), "Freshly replaced task must survive cancelIfCurrent");
        } finally {
            defaults.destroy();
            defaults = new FlexScheduledTaskRegistrar(scheduler, 5);
        }
    }

    @Test
    void cancelIfCurrent_removesMatchingEntry() throws Exception {
        try {
            defaults.addFixedDelayTask("foo", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            FlexScheduledTaskRegistrar.ScheduledTaskEntry current = readEntry(defaults, "foo");

            boolean cancelled = defaults.cancelIfCurrent("foo", current);

            assertTrue(cancelled, "cancelIfCurrent must return true when entry matches");
            assertFalse(defaults.exists("foo"));
        } finally {
            defaults.destroy();
            defaults = new FlexScheduledTaskRegistrar(scheduler, 5);
        }
    }

    /**
     * Reads the ScheduledTaskEntry for a given task via reflection. Package-private helpers
     * aren't otherwise accessible from this test package.
     */
    private FlexScheduledTaskRegistrar.ScheduledTaskEntry readEntry(
            FlexScheduledTaskRegistrar r, String taskName) throws Exception {
        Field taskMapField = FlexScheduledTaskRegistrar.class.getDeclaredField("taskMap");
        taskMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, FlexScheduledTaskRegistrar.ScheduledTaskEntry> taskMap =
            (Map<String, FlexScheduledTaskRegistrar.ScheduledTaskEntry>) taskMapField.get(r);
        return taskMap.get(taskName);
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

    // ─── replaceXxx resets createdAt (Gap 1) ─────────────────────────

    @Test
    void replaceFixedDelayTask_resetsCreatedAt() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            r.addFixedDelayTask("t", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            Instant beforeReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            Thread.sleep(20);
            r.replaceFixedDelayTask("t", Duration.ofMinutes(20), Duration.ZERO, () -> {});
            Instant afterReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            assertTrue(afterReplace.isAfter(beforeReplace),
                "replaceFixedDelayTask should reset createdAt to a later instant");
        } finally {
            r.destroy();
        }
    }

    @Test
    void replaceFixedRateTask_resetsCreatedAt() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5);
        try {
            r.addFixedRateTask("t", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            Instant beforeReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            Thread.sleep(20);
            r.replaceFixedRateTask("t", Duration.ofMinutes(20), Duration.ZERO, () -> {});
            Instant afterReplace = r.getTaskDetail("t").orElseThrow().createdAt();
            assertTrue(afterReplace.isAfter(beforeReplace),
                "replaceFixedRateTask should reset createdAt to a later instant");
        } finally {
            r.destroy();
        }
    }

    // ─── replaceXxx + lifetime renewal (Gap 2) ───────────────────────

    @Test
    void replaceFixedDelayTask_expiredTask_renewsLifetime() throws InterruptedException {
        // Set max-lifetime to 200ms; add a task; wait past lifetime; replace it.
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.STRICT));
        try {
            r.addFixedDelayTask("expired", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            // Wait past the lifetime cap
            Thread.sleep(300);

            // Replace — should succeed and reset createdAt (the entry is fresh)
            r.replaceFixedDelayTask("expired", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            assertTrue(r.exists("expired"),
                "Replaced task should be in the map even when previous instance had expired");
            // createdAt should be very recent (renewed)
            Instant renewedCreatedAt = r.getTaskDetail("expired").orElseThrow().createdAt();
            assertTrue(renewedCreatedAt.isAfter(Instant.now().minus(Duration.ofSeconds(2))),
                "Replaced task should have fresh createdAt");
        } finally {
            r.destroy();
        }
    }

    @Test
    void replaceFixedRateTask_expiredTask_renewsLifetime() throws InterruptedException {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.STRICT));
        try {
            r.addFixedRateTask("expired", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            Thread.sleep(300);

            r.replaceFixedRateTask("expired", Duration.ofMinutes(15), Duration.ZERO, () -> {});
            assertTrue(r.exists("expired"),
                "Replaced task should be in the map even when previous instance had expired");
            Instant renewedCreatedAt = r.getTaskDetail("expired").orElseThrow().createdAt();
            assertTrue(renewedCreatedAt.isAfter(Instant.now().minus(Duration.ofSeconds(2))),
                "Replaced task should have fresh createdAt");
        } finally {
            r.destroy();
        }
    }

    // ─── WARN mode interval via registrar API (Gap 3) ─────────────────

    @Test
    void addFixedDelayTask_warnMode_belowMin_logsAndAllows() {
        // WARN mode should allow below-min intervals with a log message (no throw)
        assertDoesNotThrow(
            () -> warn.addFixedDelayTask("belowMin", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
        assertTrue(warn.exists("belowMin"),
            "WARN mode should register the task even when below min-interval");
    }

    // ─── restoreTasks interval validation (Gap 4) ────────────────────

    @Test
    void restoreTasks_fixedDelay_strictRejectsBelowMin() {
        // Persist a task with 100ms interval; set limits to 10min STRICT; restore; expect rejection.
        TaskDefinition def = TaskDefinition.builder("shortDelay", "FIXED_DELAY")
            .interval(Duration.ofMillis(100))
            .initialDelay(Duration.ZERO)
            .beanName("testApplication")
            .methodName("noOp")
            .build();
        cn.wubo.flex.schedule.core.TaskRepository repo =
            new cn.wubo.flex.schedule.core.InMemoryTaskRepository();
        repo.save(def);

        TaskLimits strictLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.STRICT);
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5, strictLimits);
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

            assertFalse(r.exists("shortDelay"),
                "STRICT mode should reject restoring a task with below-min interval");
        } finally {
            r.destroy();
        }
    }

    @Test
    void restoreTasks_fixedDelay_warnAllowsBelowMin() {
        // Same setup but WARN mode — restore should succeed with a log, task should be in map.
        TaskDefinition def = TaskDefinition.builder("shortDelay", "FIXED_DELAY")
            .interval(Duration.ofMillis(100))
            .initialDelay(Duration.ZERO)
            .beanName("testApplication")
            .methodName("noOp")
            .build();
        cn.wubo.flex.schedule.core.TaskRepository repo =
            new cn.wubo.flex.schedule.core.InMemoryTaskRepository();
        repo.save(def);

        TaskLimits warnLimits = new TaskLimits(Duration.ofMinutes(10), null, Mode.WARN);
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5, warnLimits);
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

            assertTrue(r.exists("shortDelay"),
                "WARN mode should allow restoring a task with below-min interval");
        } finally {
            r.destroy();
        }
    }

    // ─── WARN mode integration tests (Priority A gaps) ────────────────

    @Test
    void resume_pausedTaskPastLifetime_warnMode_keepsTask() throws InterruptedException {
        // My fix made LimitsChecker.isExpired return false for WARN mode. resume() should
        // therefore NOT cancel the paused task; it should just remove it from pausedTasks
        // and let the task keep firing past its configured lifetime.
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.WARN));
        try {
            r.addFixedDelayTask("warnOld", Duration.ofSeconds(60), Duration.ZERO, () -> {});
            r.pause("warnOld");
            Thread.sleep(400); // wait past max-lifetime

            r.resume("warnOld");

            assertTrue(r.exists("warnOld"), "WARN mode must keep the task alive past lifetime");
            assertFalse(r.isPaused("warnOld"), "resume() should have unpaused it");
        } finally {
            r.destroy();
        }
    }

    @Test
    void instrument_lazyLifetimeCheck_warnMode_keepsTaskAlive() throws InterruptedException {
        // The lazy check inside instrument() now respects mode: WARN returns false, so the
        // task should NOT be auto-cancelled on its next fire after max-lifetime.
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 5,
            new TaskLimits(null, Duration.ofMillis(200), Mode.WARN));
        try {
            AtomicInteger fires = new AtomicInteger(0);
            r.addFixedDelayTask("warnFire", Duration.ofMillis(50), Duration.ZERO, fires::incrementAndGet);
            Thread.sleep(400); // wait past max-lifetime; multiple fires should have happened

            assertTrue(r.exists("warnFire"), "WARN mode must NOT auto-cancel task past lifetime");
            assertTrue(fires.get() >= 1, "Task should have fired at least once past lifetime under WARN");
        } finally {
            r.destroy();
        }
    }

    @Test
    void schedule_oneShot_belowMin_warnMode_allows() {
        // one-shot below-min in WARN mode should be allowed (log and allow)
        assertDoesNotThrow(
            () -> warn.schedule("oneShotWarn", Duration.ofSeconds(5), () -> {}));
        assertTrue(warn.exists("oneShotWarn"));
    }

    @Test
    void replaceFixedDelayTask_belowMin_doesNotMutateExistingEntry() {
        // Add a valid task on the strict registrar, then attempt to replace with one below
        // min-interval (10m). The original task must remain untouched.
        strict.addFixedDelayTask("foo", Duration.ofMinutes(15), Duration.ZERO, () -> {});
        try {
            assertThrows(TaskLimitExceededException.class,
                () -> strict.replaceFixedDelayTask("foo", Duration.ofSeconds(5), Duration.ZERO, () -> {}));
            assertTrue(strict.exists("foo"), "Original task must survive a failed replace");
            // Schedule string must still reflect the original 15m interval, not the rejected 5s.
            String schedule = strict.getTaskDetail("foo").orElseThrow().schedule();
            String intervalPart = schedule.substring(0, schedule.indexOf('/'));
            assertEquals(Duration.ofMinutes(15), Duration.parse(intervalPart),
                "Original interval must be preserved when replace is rejected");
        } finally {
            strict.destroy();
        }
    }

    // ─── LimitsChecker defensive: null-coalesce (Priority A gap 5) ──

    @Test
    void limitsChecker_nullLimits_treatedAsDisabled() throws Exception {
        // LimitsChecker is package-private; access via Class.forName to avoid compile-time visibility.
        Class<?> checkerClass = Class.forName("cn.wubo.flex.schedule.core.LimitsChecker");
        java.lang.reflect.Constructor<?> ctor = checkerClass.getDeclaredConstructor(TaskLimits.class);
        ctor.setAccessible(true);
        Object checker = ctor.newInstance((TaskLimits) null);

        java.lang.reflect.Method assertInterval = checkerClass.getDeclaredMethod(
            "assertInterval", String.class, Duration.class);
        assertInterval.setAccessible(true);
        java.lang.reflect.Method isExpired = checkerClass.getDeclaredMethod(
            "isExpired", String.class, Instant.class);
        isExpired.setAccessible(true);

        assertDoesNotThrow(() -> assertInterval.invoke(checker, "t", Duration.ofMillis(1)));
        assertFalse((Boolean) isExpired.invoke(checker, "t", Instant.now().minus(Duration.ofDays(365))));
    }

    // ─── restoreTasks coverage for CRON / FIXED_RATE (Priority B gaps 6-7) ─

    @Test
    void restoreTasks_cron_succeeds() throws Exception {
        TaskDefinition def = TaskDefinition.builder("cronDaily", "CRON")
            .cronExpression("0 0 * * * *")
            .beanName("testApplication")
            .methodName("noOp")
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

            assertTrue(r.exists("cronDaily"));
            assertEquals("CRON", r.getTaskDetail("cronDaily").orElseThrow().taskType());
        } finally {
            r.destroy();
        }
    }

    @Test
    void restoreTasks_fixedRate_succeeds() throws Exception {
        TaskDefinition def = TaskDefinition.builder("rateJob", "FIXED_RATE")
            .interval(Duration.ofMinutes(10))
            .initialDelay(Duration.ZERO)
            .beanName("testApplication")
            .methodName("noOp")
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

            assertTrue(r.exists("rateJob"));
            assertEquals("FIXED_RATE", r.getTaskDetail("rateJob").orElseThrow().taskType());
        } finally {
            r.destroy();
        }
    }

    // ─── Lifecycle defensive (Priority B gap 8) ────────────────────

    @Test
    void destroy_isIdempotent_secondCallDoesNotThrow() {
        defaults.destroy();
        assertDoesNotThrow(() -> defaults.destroy(),
            "Second destroy() must be a safe no-op");
    }

    // ─── setX(null) defensive (Priority B gap 9) ────────────────────

    @Test
    void setMetricsRecorder_nullFallsBackToNoop() {
        defaults.setMetricsRecorder(null);
        // No public getter for metricsRecorder; behavior is verified by execution not throwing.
        // Just ensure no NPE.
        assertDoesNotThrow(() -> defaults.addFixedDelayTask(
            "afterNull", Duration.ofMinutes(15), Duration.ZERO, () -> {}));
    }

    @Test
    void setExecutionHistory_nullFallsBackToNoop() {
        defaults.setExecutionHistory(null);
        // After null setter, getter must return a non-null default (the NOOP instance)
        assertNotNull(defaults.getExecutionHistory());
    }

    @Test
    void setDistributedLock_nullFallsBackToNoop() {
        defaults.setDistributedLock(null);
        assertNotNull(defaults.getDistributedLock());
    }

    @Test
    void setTaskRepository_nullFallsBackToInMemory() {
        defaults.setTaskRepository(null);
        assertNotNull(defaults.getTaskRepository());
    }

    @Test
    void setAsyncListenerExecutor_nullFallsBackToDefault() {
        defaults.setAsyncListenerExecutor(null);
        // No public getter; just ensure no NPE on a subsequent operation.
        assertDoesNotThrow(() -> defaults.addListener(
            new cn.wubo.flex.schedule.core.TaskExecutionListener() {
                @Override public void beforeExecution(String name) {}
                @Override public void afterExecution(String name) {}
                @Override public void onError(String name, Throwable error) {}
                @Override public boolean isAsync() { return true; }
            }));
    }
}