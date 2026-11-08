package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime behavior tests for the 1.3.0 MisfirePolicy feature (Task 3):
 * late-fire detection at the front of the registrar's instrument() pipeline and
 * pause-gap catch-up settlement in resume()/restoreTasks().
 * <p>
 * Timing discipline: intervals >= 100ms, thresholds ~50ms, polling/latch-based
 * assertions only; counts that cadence jitter could shift by one carry an
 * explicit +-1 tolerance. Catch-up bursts are counted inside short windows or
 * frozen with a follow-up pause() so regular fires cannot pollute exact counts.
 * </p>
 */
class MisfirePolicyBehaviorTest {

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
        FlexScheduledTaskRegistrar r = new FlexScheduledTaskRegistrar(scheduler, 2);
        r.setExecutionHistory(new InMemoryExecutionHistory());
        if (recorder != null) {
            r.setMetricsRecorder(recorder);
        }
        registrars.add(r);
        return r;
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

    private static boolean hasMisfireSkip(FlexScheduledTaskRegistrar registrar, String taskName) {
        return registrar.getExecutionHistory().getHistory(taskName, 100).stream()
                .anyMatch(rec -> !rec.success() && rec.error() != null
                        && rec.error().startsWith("MISFIRE_SKIPPED"));
    }

    private static long successCount(FlexScheduledTaskRegistrar registrar, String taskName) {
        return registrar.getExecutionHistory().getHistory(taskName, 100).stream()
                .filter(ExecutionRecord::success).count();
    }

    private static long failedCount(FlexScheduledTaskRegistrar registrar, String taskName) {
        return registrar.getExecutionHistory().getHistory(taskName, 100).stream()
                .filter(rec -> !rec.success()).count();
    }

    @SuppressWarnings("unchecked")
    private static FlexScheduledTaskRegistrar.ScheduledTaskEntry readEntry(
            FlexScheduledTaskRegistrar r, String taskName) throws Exception {
        Field taskMapField = FlexScheduledTaskRegistrar.class.getDeclaredField("taskMap");
        taskMapField.setAccessible(true);
        Map<String, FlexScheduledTaskRegistrar.ScheduledTaskEntry> taskMap =
                (Map<String, FlexScheduledTaskRegistrar.ScheduledTaskEntry>) taskMapField.get(r);
        return taskMap.get(taskName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> readExpectedFireMap(FlexScheduledTaskRegistrar r) throws Exception {
        Field field = FlexScheduledTaskRegistrar.class.getDeclaredField("nextExpectedFire");
        field.setAccessible(true);
        return (Map<String, Instant>) field.get(r);
    }

    /** Counts whole-second boundaries in (from, to] — the missed cron fires for "* * * * * *". */
    private static int countSecondBoundaries(Instant from, Instant to) {
        ZonedDateTime t = ZonedDateTime.ofInstant(from, ZoneId.systemDefault());
        CronExpression expr = CronExpression.parse("* * * * * *");
        int count = 0;
        while (count <= 20) {
            ZonedDateTime next = expr.next(t);
            if (next == null || next.toInstant().isAfter(to)) {
                break;
            }
            count++;
            t = next;
        }
        return count;
    }

    /** Recording MetricsRecorder stub capturing recordMisfire invocations. */
    private static final class StubRecorder implements MetricsRecorder {
        final AtomicInteger executions = new AtomicInteger();
        final List<String> misfires = new CopyOnWriteArrayList<>();

        @Override
        public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
            executions.incrementAndGet();
        }

        @Override
        public void setActiveTaskCountSupplier(Supplier<Integer> supplier) {
        }

        @Override
        public void recordMisfire(String taskName, String taskType, MisfirePolicy policy) {
            misfires.add(taskName + "|" + taskType + "|" + policy);
        }
    }

    /**
     * Registers a 100ms fixed-delay task on a pool-size=1 registrar whose FIRST
     * execution schedules a 300ms blocking occupant from inside itself. This makes
     * the second fire deterministically ~200ms late (queued behind the occupant) so
     * it is the fire judged for lateness — the first fire only seeds the expected-fire
     * baseline and is never judged.
     */
    private AtomicInteger registerWithInlineBlocker(FlexScheduledTaskRegistrar registrar,
                                                      DefaultFlexScheduledTaskService service,
                                                      String taskName, MisfirePolicy policy,
                                                      Duration threshold) throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch firstFire = new CountDownLatch(1);
        service.task(taskName)
                .fixedDelay(Duration.ofMillis(100), Duration.ofMillis(50))
                .misfirePolicy(policy)
                .misfireThreshold(threshold)
                .register(() -> {
                    counter.incrementAndGet();
                    if (firstFire.getCount() > 0) {
                        firstFire.countDown();
                        // Zero-delay one-shot occupant: on pool=1 it takes the thread
                        // immediately after this execution returns, holding it ~300ms.
                        registrar.schedule(taskName + "-blocker", Duration.ZERO, () -> sleep(300));
                    }
                });
        assertTrue(firstFire.await(5, TimeUnit.SECONDS), "first (on-time) fire should happen");
        return counter;
    }

    /** Bean used by the restoreTasks scenario; BeanMethodRunnable invokes tick(). */
    public static class CountingBean {
        final AtomicInteger count = new AtomicInteger();

        public void tick() {
            count.incrementAndGet();
        }
    }

    // ─── 1. runtime lateness below threshold: business as usual ───────

    @Test
    void lateExecution_belowThreshold_executesNormally_noMisfireRecorded() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-below-", recorder);
        DefaultFlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        // Strictest policy (SKIP) but generous threshold (500ms): the ~250ms-late
        // fire must NOT count as a misfire and must execute normally.
        AtomicInteger counter = registerWithInlineBlocker(r, service, "belowTask",
                MisfirePolicy.SKIP, Duration.ofMillis(500));

        awaitTrue(() -> counter.get() >= 2, 3000, "late-but-below-threshold fire must execute");
        Thread.sleep(150);

        assertTrue(recorder.misfires.isEmpty(),
                "no misfire may be recorded below threshold, got " + recorder.misfires);
        assertEquals(0, failedCount(r, "belowTask"), "no skipped/failed records expected");
    }

    // ─── 2. runtime lateness above threshold + SKIP ───────────────────

    @Test
    void lateExecution_aboveThreshold_skipPolicy_skipsExecution() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-skip-", recorder);
        DefaultFlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        // The second fire is ~200ms late (> 50ms threshold) behind the inline blocker
        // and must be SKIPPED: no delegate execution, MISFIRE_SKIPPED history record.
        AtomicInteger counter = registerWithInlineBlocker(r, service, "skipTask",
                MisfirePolicy.SKIP, Duration.ofMillis(50));

        awaitTrue(() -> hasMisfireSkip(r, "skipTask"), 3000, "MISFIRE_SKIPPED record should appear");
        // Freeze the task before the next regular fire (~100ms away) for exact counts.
        r.pause("skipTask");
        Thread.sleep(50);

        assertEquals(1, counter.get(), "skipped fire must NOT increment the execution count");
        List<ExecutionRecord> history = r.getExecutionHistory().getHistory("skipTask", 100);
        assertEquals(2, history.size(), "expected the first success + one MISFIRE_SKIPPED record");
        ExecutionRecord skipped = history.stream().filter(rec -> !rec.success()).findFirst().orElseThrow();
        assertTrue(skipped.error().startsWith("MISFIRE_SKIPPED"),
                "error must start with MISFIRE_SKIPPED, was: " + skipped.error());
        assertEquals("FIXED_DELAY", skipped.taskType());
        assertEquals(1, successCount(r, "skipTask"));
        assertTrue(recorder.misfires.contains("skipTask|FIXED_DELAY|SKIP"),
                "recordMisfire must be called with task/type/policy, got " + recorder.misfires);

        // The task must stay alive and keep firing after the skip.
        r.resume("skipTask");
        awaitTrue(() -> counter.get() >= 2, 3000, "task must continue after a misfire skip");
    }

    // ─── 3. runtime lateness above threshold + FIRE_ONCE ──────────────

    @Test
    void lateExecution_aboveThreshold_fireOnce_stillExecutes() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-once-", recorder);
        DefaultFlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);

        AtomicInteger counter = registerWithInlineBlocker(r, service, "fireOnceTask",
                MisfirePolicy.FIRE_ONCE, Duration.ofMillis(50));

        awaitTrue(() -> !recorder.misfires.isEmpty(), 3000, "misfire should be recorded");
        awaitTrue(() -> counter.get() >= 2, 3000, "late fire must still execute under FIRE_ONCE");
        // Freeze before the next regular fire (~100ms after the late one) for exact counts.
        r.pause("fireOnceTask");
        Thread.sleep(50);

        assertEquals(2, counter.get(), "on-time fire + late fire executed, no skip, no extra");
        assertEquals(1, recorder.misfires.size(), "exactly one misfire recorded");
        assertEquals("fireOnceTask|FIXED_DELAY|FIRE_ONCE", recorder.misfires.get(0));
        assertEquals(0, failedCount(r, "fireOnceTask"), "FIRE_ONCE must not create failed records");
        assertEquals(2, successCount(r, "fireOnceTask"), "both fires recorded as successes");
    }

    // ─── 4. threshold boundary: strictly-greater semantics ────────────

    @Test
    void thresholdBoundary_exactlyEqual_notMisfire() throws Exception {
        // Exact equality between lateness and threshold cannot be constructed with a
        // real scheduler (nanosecond precision), so the decision method is exercised
        // directly with pinned instants — same reflection-test-seam precedent as
        // FlexScheduledTaskLimitsTest's LimitsChecker tests.
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-boundary-", recorder);
        r.addFixedDelayTask("boundaryTask", Duration.ofSeconds(10), Duration.ofSeconds(60), () -> {});
        r.setMisfireConfig("boundaryTask", MisfirePolicy.SKIP, Duration.ofMillis(50));

        Map<String, Instant> expectedMap = readExpectedFireMap(r);
        Instant base = Instant.now().minusSeconds(300);
        expectedMap.put("boundaryTask", base);

        Method handleMisfire = FlexScheduledTaskRegistrar.class
                .getDeclaredMethod("handleMisfire", String.class, Instant.class);
        handleMisfire.setAccessible(true);

        // Each call advances the tracker, so re-seed expected=base before each probe.
        // lateness == threshold (exactly 50ms) → NOT a misfire
        expectedMap.put("boundaryTask", base);
        assertFalse((Boolean) handleMisfire.invoke(r, "boundaryTask", base.plusMillis(50)),
                "lateness exactly equal to threshold must not be a misfire");
        assertTrue(recorder.misfires.isEmpty(), "no misfire may be recorded at exact threshold");

        // lateness < threshold → NOT a misfire
        expectedMap.put("boundaryTask", base);
        assertFalse((Boolean) handleMisfire.invoke(r, "boundaryTask", base.plusMillis(49)),
                "lateness below threshold must not be a misfire");
        assertTrue(recorder.misfires.isEmpty());

        // lateness == threshold + 1ns → misfire (strictly greater), SKIP → skip
        expectedMap.put("boundaryTask", base);
        assertTrue((Boolean) handleMisfire.invoke(r, "boundaryTask", base.plusMillis(50).plusNanos(1)),
                "lateness strictly greater than threshold must be a misfire");
        assertEquals(1, recorder.misfires.size());
        assertEquals("boundaryTask|FIXED_DELAY|SKIP", recorder.misfires.get(0));
        assertTrue(hasMisfireSkip(r, "boundaryTask"),
                "SKIP decision must leave a MISFIRE_SKIPPED history record");
    }

    // ─── 4b. FIXED_DELAY: delegate runtime must not count as lateness ──

    @Test
    void fixedDelay_delegateLongerThanThreshold_onScheduleFires_notMisfire() throws Exception {
        // Fixed-delay contract: next fire = completion + interval. A delegate that
        // runs LONGER than the misfire threshold but fires exactly on schedule must
        // never be judged as late (regression guard for the start-based baseline,
        // which counted delegate runtime as lateness and caused false SKIPs /
        // permanent FIRE_ONCE warnings).
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-slow-delegate-", recorder);
        DefaultFlexScheduledTaskService service = new DefaultFlexScheduledTaskService(r);
        AtomicInteger counter = new AtomicInteger();

        service.task("slowDelegate")
                .fixedDelay(Duration.ofMillis(100), Duration.ZERO)
                .misfirePolicy(MisfirePolicy.SKIP)          // strictest policy: a false
                .misfireThreshold(Duration.ofMillis(50))    // misfire would SKIP fires
                .register(() -> {
                    counter.incrementAndGet();
                    sleep(150); // delegate runtime > threshold
                });

        // Each cycle takes ~250ms (150ms delegate + 100ms delay); wait for >= 4 fires.
        awaitTrue(() -> counter.get() >= 4, 10_000,
                "on-schedule fires must keep executing despite a slow delegate");
        // Freeze the cadence, then wait longer than one delegate runtime so any
        // in-flight execution completes and its success record lands in history.
        r.pause("slowDelegate");
        Thread.sleep(300);

        assertTrue(recorder.misfires.isEmpty(),
                "no misfire may be recorded for on-schedule fixed-delay fires, got " + recorder.misfires);
        assertFalse(hasMisfireSkip(r, "slowDelegate"),
                "SKIP must not fire: delegate runtime is not lateness");
        assertEquals(0, failedCount(r, "slowDelegate"));
        assertEquals(counter.get(), successCount(r, "slowDelegate"),
                "every fire should be recorded as a success");
        assertTrue(counter.get() >= 4, "execution count must grow normally, got " + counter.get());
    }

    // ─── 5. resume + SKIP: no catch-up ────────────────────────────────

    @Test
    void resumeAfterPause_skipPolicy_noCatchUp() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-resume-skip-", recorder);
        AtomicInteger counter = new AtomicInteger();
        r.addFixedDelayTask("skipResume", Duration.ofMillis(100), Duration.ZERO, counter::incrementAndGet);
        r.setMisfireConfig("skipResume", MisfirePolicy.SKIP, Duration.ofSeconds(5));

        awaitTrue(() -> counter.get() >= 1, 3000, "task should fire before pause");
        r.pause("skipResume");
        Thread.sleep(400); // ~4 fires missed while paused
        int before = counter.get();
        r.resume("skipResume");
        // 80ms window: a SKIP burst would land immediately; at most one regular
        // 100ms-cadence fire can fit inside the window.
        Thread.sleep(80);
        int delta = counter.get() - before;

        assertTrue(delta <= 1,
                "SKIP must not catch up; at most one regular fire may land in the window, delta=" + delta);
        assertTrue(recorder.misfires.isEmpty(), "no runtime misfire expected: " + recorder.misfires);
        r.pause("skipResume");
    }

    // ─── 6. resume + FIRE_ONCE: exactly one catch-up when a firing was missed ──

    @Test
    void resumeAfterPause_fireOnce_catchesUpExactlyOnce() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-resume-once-", null);
        AtomicInteger counter = new AtomicInteger();
        // FIRE_ONCE catches up only when the pause gap actually missed a firing,
        // so the pause below must span at least one 2s interval. After the
        // catch-up the counter must stay stable (poll-to-stable).
        r.addFixedDelayTask("onceResume", Duration.ofSeconds(2), Duration.ZERO, counter::incrementAndGet);
        r.setMisfireConfig("onceResume", MisfirePolicy.FIRE_ONCE, Duration.ofSeconds(30));

        awaitTrue(() -> counter.get() >= 1, 3000, "first fire");
        r.pause("onceResume");
        Thread.sleep(2400); // > one interval: exactly one firing missed
        r.resume("onceResume");

        awaitTrue(() -> counter.get() >= 2, 3000, "FIRE_ONCE should catch up once on resume");
        Thread.sleep(400); // stable-poll window; next regular fire is ~2s away
        assertEquals(2, counter.get(), "catch-up must be exactly one execution");
    }

    // ─── 7. resume + FIRE_IMMEDIATELY: replays missed count ───────────

    @Test
    void resumeAfterPause_fireImmediately_catchesUpMissedCount() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-resume-imm-", null);
        AtomicInteger counter = new AtomicInteger();
        Duration interval = Duration.ofMillis(150);
        r.addFixedDelayTask("immResume", interval, Duration.ZERO, counter::incrementAndGet);
        r.setMisfireConfig("immResume", MisfirePolicy.FIRE_IMMEDIATELY, Duration.ofSeconds(30));

        awaitTrue(() -> counter.get() >= 1, 3000, "first fire");
        Instant t1 = Instant.now();
        r.pause("immResume");
        Thread.sleep(620); // ~4 missed fires at 150ms cadence
        Instant t2 = Instant.now();
        int before = counter.get();
        r.resume("immResume");
        Thread.sleep(80); // catch-up burst window; +-1 tolerance absorbs one regular fire
        int delta = counter.get() - before;

        long missed = Duration.between(t1, t2).toMillis() / interval.toMillis();
        int expected = (int) Math.min(missed, 10);
        assertTrue(Math.abs(delta - expected) <= 1,
                "catch-up should replay the missed count (4±1): delta=" + delta + ", expected=" + expected);
    }

    // ─── 8. resume + FIRE_IMMEDIATELY: capped at MAX_CATCH_UP=10 ──────

    @Test
    void resumeAfterPause_fireImmediately_capsAtMaxCatchUp() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-cap-", null);
        AtomicInteger counter = new AtomicInteger();
        Duration interval = Duration.ofMillis(200);
        r.addFixedDelayTask("capTask", interval, Duration.ZERO, counter::incrementAndGet);
        r.setMisfireConfig("capTask", MisfirePolicy.FIRE_IMMEDIATELY, Duration.ofSeconds(30));

        awaitTrue(() -> counter.get() >= 1, 3000, "first fire");

        // Occupy the single scheduler thread for the whole pause so that NO fire
        // (not even a pause-skip) runs: exactly one stale regular fire stays queued
        // and drains first when the blocker is released, then the catch-up burst.
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        r.schedule("capBlocker", Duration.ofMillis(10), () -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS), "blocker should start");

        Instant t1 = Instant.now();
        r.pause("capTask");
        Thread.sleep(2600); // ~13 missed fires — unambiguously more than MAX_CATCH_UP
        Instant t2 = Instant.now();
        long missed = Duration.between(t1, t2).toMillis() / interval.toMillis();
        assertTrue(missed > 10, "test premise: missed (" + missed + ") must exceed MAX_CATCH_UP=10");

        int before = counter.get();
        r.resume("capTask");
        releaseBlocker.countDown();

        // Drain order (pool=1, by trigger time): 1 stale regular fire, then 10 catch-ups.
        awaitTrue(() -> counter.get() - before >= 11, 5000,
                "1 stale regular + 10 catch-up executions expected");
        r.pause("capTask"); // freeze before the next regular fire (+200ms after the stale one)
        Thread.sleep(300);

        assertEquals(11, counter.get() - before,
                "catch-up must cap at MAX_CATCH_UP=10 (plus the single queued stale fire)");
    }

    // ─── 9. resume + FIRE_IMMEDIATELY on cron: missed cron firings ────

    @Test
    void resumeAfterPause_cronTask_countsMissedCronFirings() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-cron-", null);
        AtomicInteger counter = new AtomicInteger();
        r.addCronTask("cronCatchUp", "* * * * * *", counter::incrementAndGet);
        r.setMisfireConfig("cronCatchUp", MisfirePolicy.FIRE_IMMEDIATELY, Duration.ofSeconds(30));

        Instant t1 = Instant.now();
        r.pause("cronCatchUp");

        // Stay paused >= ~2.4s, then resume shortly AFTER a second boundary so the
        // next regular cron fire is >= 750ms away and cannot pollute the 150ms window.
        long target = t1.toEpochMilli() + 2400;
        Instant t2;
        while (true) {
            long now = System.currentTimeMillis();
            long phase = now % 1000;
            if (now >= target && phase >= 100 && phase < 250) {
                t2 = Instant.now();
                break;
            }
            Thread.sleep(20);
        }

        int before = counter.get();
        r.resume("cronCatchUp");
        Thread.sleep(150);
        int delta = counter.get() - before;

        int boundaries = countSecondBoundaries(t1, t2);
        assertTrue(boundaries >= 2, "precondition: at least 2 missed cron fires, got " + boundaries);
        assertTrue(Math.abs(delta - boundaries) <= 1,
                "catch-up should replay missed cron firings (2±1): delta=" + delta
                        + ", boundaries=" + boundaries);
        assertTrue(delta >= 1, "FIRE_IMMEDIATELY must replay at least one missed cron firing");
        r.pause("cronCatchUp");
    }

    // ─── 10. re-pause during catch-up aborts it ───────────────────────

    @Test
    void pauseAgainDuringCatchUp_stopsCatchUp() throws Exception {
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-repause-", null);
        AtomicInteger counter = new AtomicInteger();
        Runnable slowTask = () -> {
            counter.incrementAndGet();
            sleep(80); // slow delegate stretches the serial catch-up burst over ~800ms
        };
        r.addFixedDelayTask("repauseTask", Duration.ofMillis(100), Duration.ZERO, slowTask);
        r.setMisfireConfig("repauseTask", MisfirePolicy.FIRE_IMMEDIATELY, Duration.ofSeconds(30));

        awaitTrue(() -> counter.get() >= 1, 3000, "first fire");
        int beforePause = counter.get();
        r.pause("repauseTask");
        Thread.sleep(1500); // ~15 missed → catch-up would be 10 (~600ms serial)
        r.resume("repauseTask");
        Thread.sleep(250); // let ~3-5 catch-up executions run
        r.pause("repauseTask"); // must abort the remaining catch-ups

        Thread.sleep(400);
        int c1 = counter.get();
        Thread.sleep(300);
        int c2 = counter.get();

        assertEquals(c1, c2, "no executions may occur after re-pausing (catch-up aborted, regulars skipped)");
        int caughtUp = c1 - beforePause;
        assertTrue(caughtUp >= 2, "some catch-up executions should have run before re-pause, got " + caughtUp);
        assertTrue(caughtUp <= 8, "catch-up must abort below the full 10 executions, got " + caughtUp);
    }

    // ─── 11. MetricsRecorder receives recordMisfire ───────────────────

    @Test
    void misfireRecordedInMetricsRecorder() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-metrics-", recorder);
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch firstFire = new CountDownLatch(1);
        r.addFixedRateTask("metricsMisfire", Duration.ofMillis(100), Duration.ofMillis(50), () -> {
            counter.incrementAndGet();
            if (firstFire.getCount() > 0) {
                firstFire.countDown();
                r.schedule("metricsBlocker", Duration.ZERO, () -> sleep(300));
            }
        });
        // Configured synchronously right after registration, long before the +50ms first fire.
        r.setMisfireConfig("metricsMisfire", MisfirePolicy.FIRE_ONCE, Duration.ofMillis(50));

        assertTrue(firstFire.await(5, TimeUnit.SECONDS), "first fire should happen");
        awaitTrue(() -> !recorder.misfires.isEmpty(), 3000,
                "recordMisfire(taskName, taskType, policy) should be called");

        assertEquals("metricsMisfire|FIXED_RATE|FIRE_ONCE", recorder.misfires.get(0),
                "stub must receive task name, task type and policy");
        // FIRE_ONCE: the late fire(s) still execute — counter keeps growing.
        int atDetection = counter.get();
        awaitTrue(() -> counter.get() > atDetection || atDetection >= 2, 3000,
                "task should keep executing after a FIRE_ONCE misfire");
        r.pause("metricsMisfire");
    }

    // ─── 12. restoreTasks: paused definition uses updatedAt as gap start ──

    @Test
    void restoreTasks_pausedDefinition_usesUpdatedAtAsGapStart() throws Exception {
        // Wire a minimal Spring context so BeanMethodRunnable can resolve the bean
        // (same pattern as FlexScheduledTaskLimitsTest restore tests). SpringContextUtils
        // holds STATIC, first-write-wins state: capture the previous value and restore it
        // in finally (disclosed pattern from RegistrarRestoreTasksEdgeTest) so this test
        // neither depends on nor pollutes later classes (BeanMethodRunnableTest is the
        // most exposed victim of a leaked closed context).
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        Object previousStaticContext = field.get(null);
        field.set(null, null);

        org.springframework.context.support.StaticApplicationContext ctx =
                new org.springframework.context.support.StaticApplicationContext();
        try {
            CountingBean bean = new CountingBean();
            ctx.getBeanFactory().registerSingleton("countingBean", bean);
            ctx.refresh();
            new SpringContextUtils().setApplicationContext(ctx);

            // pausedAt is anchored at updatedAt; the gap must span at least one
            // 5s interval for FIRE_ONCE to catch up ("fire once IF missed").
            Instant updatedAt = Instant.now().minusSeconds(6);
            TaskDefinition def = TaskDefinition.builder("restoredPaused", "FIXED_DELAY")
                    .interval(Duration.ofSeconds(5))     // long interval: no regular fire in-window
                    .initialDelay(Duration.ofSeconds(10))
                    .beanName("countingBean")
                    .methodName("tick")
                    .paused(true)
                    .createdAt(updatedAt)
                    .updatedAt(updatedAt)
                    .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                    .misfireThreshold(Duration.ofSeconds(30))
                    .build();
            TaskRepository repo = new InMemoryTaskRepository();
            repo.save(def);

            FlexScheduledTaskRegistrar r = newRegistrar(2, "misfire-restore-", null);
            r.setTaskRepository(repo);
            r.restoreTasks();

            assertTrue(r.isPaused("restoredPaused"), "restored definition with paused=true must be paused");
            FlexScheduledTaskRegistrar.ScheduledTaskEntry entry = readEntry(r, "restoredPaused");
            assertNotNull(entry, "entry should exist after restore");
            assertEquals(updatedAt, entry.pausedAt(),
                    "pausedAt must be seeded from the definition's updatedAt, not the restore time");

            r.resume("restoredPaused");
            awaitTrue(() -> bean.count.get() >= 1, 3000,
                    "FIRE_ONCE catch-up should run once after resuming the restored task");
            Thread.sleep(400); // stable-poll window; next regular fire is >= 5s away
            assertEquals(1, bean.count.get(), "exactly one catch-up execution");
        } finally {
            ctx.close();
            field.set(null, previousStaticContext);
        }
    }

    // ─── 13. ONE_SHOT ignores the misfire policy entirely ─────────────

    @Test
    void oneShotTask_ignoresMisfirePolicy() throws Exception {
        StubRecorder recorder = new StubRecorder();
        FlexScheduledTaskRegistrar r = newRegistrar(1, "misfire-oneshot-", recorder);
        CountDownLatch executed = new CountDownLatch(1);
        r.schedule("oneShotMisfire", Duration.ofMillis(50), executed::countDown);
        // SKIP + a 1ms threshold would skip any late recurring fire; one-shots must
        // ignore the policy entirely (setMisfireConfig is a tolerated no-op here).
        r.setMisfireConfig("oneShotMisfire", MisfirePolicy.SKIP, Duration.ofMillis(1));

        // Make the one-shot fire ~250ms late on the single-thread pool.
        r.schedule("oneShotBlocker", Duration.ofMillis(10), () -> sleep(300));

        assertTrue(executed.await(5, TimeUnit.SECONDS), "late one-shot task must still execute");
        Thread.sleep(100);
        assertTrue(recorder.misfires.isEmpty(), "one-shot tasks must never record misfires");
        assertEquals(0, failedCount(r, "oneShotMisfire"), "one-shot must not be skipped");
        assertEquals(1, successCount(r, "oneShotMisfire"));
    }
}
