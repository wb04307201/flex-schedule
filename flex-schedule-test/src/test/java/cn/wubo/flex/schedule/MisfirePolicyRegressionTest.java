package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the defects found in the post-1.3.0 misfire review:
 * <ol>
 *   <li>CRON expected-fire baseline must be completion-based (Spring's CronTrigger
 *       schedules the next fire from completion), so a delegate that runs past a
 *       cron boundary is never flagged late on every subsequent fire.</li>
 *   <li>FIRE_ONCE pause-gap catch-up must only fire when at least one firing was
 *       actually missed during the gap ("fire once IF missed").</li>
 *   <li>Catch-up bursts must not overlap regular fires of the same task.</li>
 *   <li>countMissedFirings must survive sub-millisecond intervals (no division by
 *       zero) and huge gaps (no negative int overflow).</li>
 *   <li>FIXED_RATE grid advance must be arithmetic, not an unbounded iteration
 *       loop, when the baseline is far behind.</li>
 *   <li>Failed replace/add must leave instrumentedRunnables pointing at the
 *       runnable that is actually scheduled (no stale/leaked entries).</li>
 * </ol>
 */
class MisfirePolicyRegressionTest {

    private final List<FlexScheduledTaskRegistrar> registrars = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (FlexScheduledTaskRegistrar r : registrars) {
            try {
                r.destroy();
            } catch (Exception ignored) {
            }
        }
        registrars.clear();
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private FlexScheduledTaskRegistrar newRegistrar(int poolSize, String prefix, MetricsRecorder recorder) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        FlexScheduledTaskRegistrar r = newRegistrarOn(scheduler, recorder);
        registrars.add(r);
        return r;
    }

    private FlexScheduledTaskRegistrar newRegistrarOn(ThreadPoolTaskScheduler scheduler, MetricsRecorder recorder) {
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 2);
        r.setExecutionHistory(new InMemoryExecutionHistory());
        if (recorder != null) {
            r.setMetricsRecorder(recorder);
        }
        return r;
    }

    /** Registrar whose Spring scheduling hooks can be sabotaged to throw once. */
    private static final class FailingRegistrar extends FlexScheduledTaskRegistrar {
        volatile boolean failCron;
        volatile boolean failFixedDelay;

        FailingRegistrar(ThreadPoolTaskScheduler scheduler) {
            super(scheduler, 2);
            setExecutionHistory(new InMemoryExecutionHistory());
        }

        @Override
        public ScheduledTask scheduleCronTask(CronTask task) {
            if (failCron) {
                throw new IllegalStateException("boom-cron");
            }
            return super.scheduleCronTask(task);
        }

        @Override
        public ScheduledTask scheduleFixedDelayTask(FixedDelayTask task) {
            if (failFixedDelay) {
                throw new IllegalStateException("boom-fixed-delay");
            }
            return super.scheduleFixedDelayTask(task);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Runnable> readInstrumentedMap(FlexScheduledTaskRegistrar r) throws Exception {
        Field field = FlexScheduledTaskRegistrar.class.getDeclaredField("instrumentedRunnables");
        field.setAccessible(true);
        return (Map<String, Runnable>) field.get(r);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMisfireConfigs(FlexScheduledTaskRegistrar r) throws Exception {
        Field field = FlexScheduledTaskRegistrar.class.getDeclaredField("misfireConfigs");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(r);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> readExpectedFireMap(FlexScheduledTaskRegistrar r) throws Exception {
        Field field = FlexScheduledTaskRegistrar.class.getDeclaredField("nextExpectedFire");
        field.setAccessible(true);
        return (Map<String, Instant>) field.get(r);
    }

    private static Object invokePrivate(FlexScheduledTaskRegistrar r, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = FlexScheduledTaskRegistrar.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(r, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static boolean hasMisfireSkip(FlexScheduledTaskRegistrar registrar, String taskName) {
        return registrar.getExecutionHistory().getHistory(taskName, 100).stream()
                .anyMatch(rec -> !rec.success() && rec.error() != null
                        && rec.error().startsWith("MISFIRE_SKIPPED"));
    }

    private static long successCount(FlexScheduledTaskRegistrar registrar, String taskName) {
        return registrar.getExecutionHistory().getHistory(taskName, 100).stream()
                .filter(ExecutionRecord::success).count();
    }

    /** Recording MetricsRecorder stub capturing recordMisfire invocations. */
    private static final class StubRecorder implements MetricsRecorder {
        final List<String> misfires = new CopyOnWriteArrayList<>();

        @Override
        public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
        }

        @Override
        public void setActiveTaskCountSupplier(Supplier<Integer> supplier) {
        }

        @Override
        public void recordMisfire(String taskName, String taskType, MisfirePolicy policy) {
            misfires.add(taskName + "|" + taskType + "|" + policy);
        }
    }

    // ─── 1. CRON baseline must be completion-based ────────────────────

    /**
     * A cron delegate that runs LONGER than the cron interval (1.3s work on a
     * "* * * * * *" schedule) must never be judged late: Spring's CronTrigger
     * schedules the next fire from the COMPLETION instant, so the expected-fire
     * baseline must be rebased the same way (like FIXED_DELAY already is).
     * With the start-based baseline every fire after a long delegate was flagged
     * as a misfire — under SKIP that means healthy executions get skipped.
     */
    @Test
    void cronDelegateLongerThanInterval_onScheduleFires_notMisfire() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-cron-slow-", recorder);
        AtomicInteger counter = new AtomicInteger();

        r.addCronTask("slowCron", "* * * * * *", () -> {
            counter.incrementAndGet();
            sleep(1300); // delegate runtime > cron interval: completion crosses a boundary
        });
        r.setMisfireConfig("slowCron", MisfirePolicy.SKIP, Duration.ofMillis(100));

        // New (completion-based) cadence: fires at ~t0, t0+2s, t0+4s.
        // Old (start-based) cadence: every second fire is SKIPPED as "late",
        // so reaching 3 successes takes ~9s and misfires pile up.
        awaitTrue(() -> successCount(r, "slowCron") >= 3, 15_000,
                "on-schedule cron fires must keep executing despite a slow delegate");

        assertTrue(recorder.misfires.isEmpty(),
                "no misfire may be recorded for on-schedule cron fires, got " + recorder.misfires);
        assertFalse(hasMisfireSkip(r, "slowCron"),
                "SKIP must not fire: crossing a cron boundary via delegate runtime is not lateness");

        // Freeze the cadence and let any in-flight delegate finish so the
        // history settles before comparing counts.
        r.pause("slowCron");
        Thread.sleep(1600);
        assertEquals(counter.get(), successCount(r, "slowCron"),
                "every fire should be recorded as a success");
    }

    // ─── 2. FIRE_ONCE must not catch up when nothing was missed ───────

    /**
     * MisfirePolicy.FIRE_ONCE promises "fire once IF missed". A pause shorter
     * than one interval misses zero firings, so resume must NOT trigger a
     * spurious immediate execution (side effects: emails, batches...).
     */
    @Test
    void resumeAfterShortPause_fireOnce_noCatchUpWhenNothingMissed() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-short-pause-", null);
        AtomicInteger counter = new AtomicInteger();
        // initialDelay 60s keeps regular fires out of the whole test window.
        r.addFixedDelayTask("shortPause", Duration.ofSeconds(10), Duration.ofSeconds(60),
                counter::incrementAndGet);
        r.setMisfireConfig("shortPause", MisfirePolicy.FIRE_ONCE, Duration.ofSeconds(30));

        r.pause("shortPause");
        Thread.sleep(300); // gap = 300ms << 10s interval → zero missed firings
        r.resume("shortPause");
        Thread.sleep(500); // catch-up window: a spurious burst would land immediately

        assertEquals(0, counter.get(),
                "FIRE_ONCE must not catch up when the pause gap missed zero firings");
    }

    // ─── 3. catch-up must not overlap regular fires ───────────────────

    /**
     * The resume catch-up burst runs asynchronously on a scheduler thread with no
     * mutual exclusion against the task's regular fires — the same delegate could
     * execute concurrently on two threads, violating the non-overlap guarantee.
     */
    @Test
    void resumeCatchUp_neverOverlapsRegularFire() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-overlap-", null);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger(1);
        AtomicInteger counter = new AtomicInteger();

        r.addFixedDelayTask("overlapTask", Duration.ofMillis(100), Duration.ZERO, () -> {
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            counter.incrementAndGet();
            sleep(80); // stretch each execution so overlaps are detectable
            inFlight.decrementAndGet();
        });
        r.setMisfireConfig("overlapTask", MisfirePolicy.FIRE_IMMEDIATELY, Duration.ofSeconds(30));

        awaitTrue(() -> counter.get() >= 1, 3000, "first fire");
        r.pause("overlapTask");
        Thread.sleep(550); // ~5 missed firings at 100ms cadence
        r.resume("overlapTask"); // catch-up burst (~5x80ms) races the resumed 100ms cadence

        // Wait for the burst plus a few regular fires to complete.
        awaitTrue(() -> counter.get() >= 8, 5000, "catch-up + regular fires should accumulate");
        Thread.sleep(150);

        assertEquals(1, maxInFlight.get(),
                "the same task must never execute concurrently (catch-up vs regular fire)");
        r.pause("overlapTask");
    }

    // ─── 4. countMissedFirings: sub-ms interval and huge gaps ─────────

    @Test
    void countMissedFirings_subMillisecondInterval_doesNotDivideByZero() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-divzero-", null);
        FlexScheduledTaskRegistrar.ScheduledTaskEntry entry =
                new FlexScheduledTaskRegistrar.ScheduledTaskEntry(() -> {}, "FIXED_RATE", "PT0.0005S/PT0S", null, false, Instant.now());

        Object missed = invokePrivate(r, "countMissedFirings",
                new Class<?>[]{FlexScheduledTaskRegistrar.ScheduledTaskEntry.class, Duration.class},
                entry, Duration.ofSeconds(1));

        assertTrue(((Number) missed).longValue() >= 0,
                "sub-millisecond intervals must count missed firings, not throw ArithmeticException");
    }

    @Test
    void countMissedFirings_hugeGap_doesNotOverflowNegative() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-overflow-", null);
        FlexScheduledTaskRegistrar.ScheduledTaskEntry entry =
                new FlexScheduledTaskRegistrar.ScheduledTaskEntry(() -> {}, "FIXED_RATE", "PT0.001S/PT0S", null, false, Instant.now());

        Object missed = invokePrivate(r, "countMissedFirings",
                new Class<?>[]{FlexScheduledTaskRegistrar.ScheduledTaskEntry.class, Duration.class},
                entry, Duration.ofDays(30));

        long value = ((Number) missed).longValue();
        // 30 days at 1ms cadence = 2,592,000,000 firings — exceeds Integer.MAX_VALUE,
        // so an (int) cast of the quotient wraps negative.
        assertTrue(value > 10, // MAX_CATCH_UP is package-private; pin its documented value
                "a 30-day gap at 1ms cadence must count far above MAX_CATCH_UP=10, got " + value);
    }

    // ─── 5. FIXED_RATE grid advance must be arithmetic ────────────────

    /**
     * The unbounded `while (!next.isAfter(actualStart)) next = next.plus(interval)`
     * loop iterates gap/interval times — a 1-day-old baseline at 1ms cadence means
     * ~86 million Instant.plus calls on a scheduler thread. Arithmetic advance must
     * finish effectively instantly.
     */
    @Test
    void advanceExpectedFire_fixedRateFarBehind_advancesArithmeticallyFast() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-grid-", null);
        FlexScheduledTaskRegistrar.ScheduledTaskEntry entry =
                new FlexScheduledTaskRegistrar.ScheduledTaskEntry(() -> {}, "FIXED_RATE", "PT0.001S/PT0S", null, false, Instant.now());

        Instant now = Instant.now();
        readExpectedFireMap(r).put("gridTask", now.minus(Duration.ofDays(10)));

        long start = System.nanoTime();
        invokePrivate(r, "advanceExpectedFire",
                new Class<?>[]{String.class, FlexScheduledTaskRegistrar.ScheduledTaskEntry.class, Instant.class},
                "gridTask", entry, now);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        Instant advanced = readExpectedFireMap(r).get("gridTask");
        assertTrue(advanced.isAfter(now), "grid must advance strictly past the actual start");
        assertTrue(elapsedMillis < 2000,
                "grid advance must be arithmetic (O(1)), took " + elapsedMillis + "ms");
    }

    // ─── 6. failed replace must not repoint instrumentedRunnables ─────

    @Test
    void replaceFailure_keepsInstrumentedRunnableOfLiveTask() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("misfire-replace-fail-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        FailingRegistrar r = new FailingRegistrar(scheduler);
        registrars.add(r);

        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        r.addFixedDelayTask("replaced", Duration.ofSeconds(60), Duration.ZERO, aCount::incrementAndGet);
        Runnable liveWrapped = readInstrumentedMap(r).get("replaced");
        assertNotNull(liveWrapped, "add must register the instrumented runnable");

        r.failFixedDelay = true;
        assertThrows(IllegalStateException.class, () ->
                r.replaceFixedDelayTask("replaced", Duration.ofSeconds(60), Duration.ZERO, bCount::incrementAndGet));

        assertTrue(r.exists("replaced"), "old task must survive the failed replace");
        assertSame(liveWrapped, readInstrumentedMap(r).get("replaced"),
                "failed replace must not repoint instrumentedRunnables at the rejected runnable");
    }

    @Test
    void addFailure_doesNotLeakInstrumentedRunnable() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("misfire-add-fail-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        FailingRegistrar r = new FailingRegistrar(scheduler);
        registrars.add(r);

        r.failCron = true;
        assertThrows(IllegalStateException.class, () ->
                r.addCronTask("leaky", "0 * * * * *", () -> {}));

        assertFalse(r.exists("leaky"), "taskMap entry must be rolled back");
        assertFalse(readInstrumentedMap(r).containsKey("leaky"),
                "failed add must not leak the instrumented runnable");
    }

    // ─── 7. builder must validate threshold BEFORE registering ────────

    @Test
    void builder_nonPositiveThreshold_throwsBeforeTaskIsRegistered() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("misfire-builder-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        FlexScheduledTaskRegistrar r = newRegistrarOn(scheduler, null);
        registrars.add(r);
        FlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        // Setter-time validation: an invalid threshold must fail fast, before
        // register() schedules anything (old behavior: the task was already live
        // when setMisfireConfig threw out of register()).
        assertThrows(IllegalArgumentException.class,
                () -> service.task("badThreshold").misfireThreshold(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> service.task("badThreshold").misfireThreshold(Duration.ofSeconds(-1)));
        assertFalse(service.exists("badThreshold"), "no task may be registered");
    }

    // ─── 8. builder without misfire calls must stay unconfigured ──────

    /**
     * FlexScheduledTaskService.setMisfireConfig's javadoc promises: "Tasks that
     * never receive a misfire configuration keep the pre-1.3.0 behavior." A
     * builder that never calls misfirePolicy()/misfireThreshold() must NOT wire
     * any config — otherwise every upgraded 1.2.x code path silently gains
     * late-fire detection and resume catch-up.
     */
    @Test
    void builder_withoutMisfireCalls_leavesTaskUnconfigured() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-exempt-", null);
        FlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        service.task("exemptTask")
                .fixedRate(Duration.ofSeconds(30))
                .register(() -> {});

        assertTrue(service.exists("exemptTask"));
        assertFalse(readMisfireConfigs(r).containsKey("exemptTask"),
                "a builder that never sets misfire config must leave the task exempt");
    }

    @Test
    void builder_withMisfireCalls_stillWiresConfig() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-configured-", null);
        FlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        service.task("configuredTask")
                .fixedRate(Duration.ofSeconds(30))
                .misfirePolicy(MisfirePolicy.SKIP)
                .misfireThreshold(Duration.ofSeconds(5))
                .register(() -> {});

        assertTrue(readMisfireConfigs(r).containsKey("configuredTask"),
                "explicit misfire calls must wire the config into the registrar");
    }

    // ─── 9. restored 1.2.x definitions must stay unconfigured ─────────

    /**
     * A definition persisted by 1.2.x has neither misfire field; on restore the
     * runtime must treat it as unconfigured (exempt), matching the pre-1.3.0
     * behavior promised in the compatibility javadoc — the registrar's null-check
     * opt-in gate must be live, not dead code.
     */
    @Test
    void restoreTasks_legacyDefinition_staysUnconfigured() throws Exception {
        // Wire a minimal Spring context so BeanMethodRunnable resolves the bean
        // (same disclosed pattern as MisfirePolicyBehaviorTest #12: capture and
        // restore the static SpringContextUtils state).
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        Object previousStaticContext = field.get(null);
        field.set(null, null);

        org.springframework.context.support.StaticApplicationContext ctx =
                new org.springframework.context.support.StaticApplicationContext();
        try {
            ctx.getBeanFactory().registerSingleton("legacyBean", new LegacyBean());
            ctx.refresh();
            new SpringContextUtils().setApplicationContext(ctx);

            // 1.2.x-era 15-arg constructor: both misfire fields null.
            Instant now = Instant.now();
            TaskDefinition legacy = new TaskDefinition(
                    "legacyRestored", "FIXED_DELAY", null, null,
                    Duration.ofSeconds(30), Duration.ofSeconds(60), null, null, null,
                    "legacyBean", "tick", List.of(), false, now, now);
            assertNull(legacy.misfirePolicy(), "legacy definition must carry no misfire policy");

            TaskRepository repo = new InMemoryTaskRepository();
            repo.save(legacy);

            FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-legacy-", null);
            r.setTaskRepository(repo);
            r.restoreTasks();

            assertTrue(r.exists("legacyRestored"), "definition must restore successfully");
            assertFalse(readMisfireConfigs(r).containsKey("legacyRestored"),
                    "restored 1.2.x definitions must stay exempt from misfire handling");
        } finally {
            ctx.close();
            field.set(null, previousStaticContext);
        }
    }

    /** Bean used by the legacy-restore scenario; BeanMethodRunnable invokes tick(). */
    public static class LegacyBean {
        final AtomicInteger count = new AtomicInteger();

        public void tick() {
            count.incrementAndGet();
        }
    }
}
