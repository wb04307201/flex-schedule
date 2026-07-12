package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private MicrometerMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new MicrometerMetricsRecorder(registry);
    }

    @Test
    void recordExecution_success_shouldIncrementCounter() {
        recorder.recordExecution("task1", "CRON", Duration.ofMillis(100), true);
        recorder.recordExecution("task1", "CRON", Duration.ofMillis(50), true);

        Counter counter = registry.find("flex.schedule.task.executions")
                .tag("taskName", "task1")
                .tag("taskType", "CRON")
                .tag("result", "success")
                .counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    @Test
    void recordExecution_failure_shouldIncrementCounter() {
        recorder.recordExecution("task2", "FIXED_DELAY", Duration.ofMillis(100), false);

        Counter counter = registry.find("flex.schedule.task.executions")
                .tag("taskName", "task2")
                .tag("taskType", "FIXED_DELAY")
                .tag("result", "failure")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordExecution_shouldRecordTimer() {
        recorder.recordExecution("task3", "FIXED_RATE", Duration.ofMillis(200), true);

        Timer timer = registry.find("flex.schedule.task.duration")
                .tag("taskName", "task3")
                .tag("taskType", "FIXED_RATE")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 200);
    }

    @Test
    void setActiveTaskCountSupplier_shouldRegisterGauge() {
        recorder.setActiveTaskCountSupplier(() -> 5);

        Gauge gauge = registry.find("flex.schedule.active.tasks").gauge();
        assertNotNull(gauge);
        assertEquals(5.0, gauge.value());
    }

    @Test
    void recordExecution_differentTags_shouldCreateSeparateMeters() {
        recorder.recordExecution("taskA", "CRON", Duration.ofMillis(100), true);
        recorder.recordExecution("taskB", "CRON", Duration.ofMillis(100), true);

        Counter counterA = registry.find("flex.schedule.task.executions")
                .tag("taskName", "taskA")
                .counter();
        Counter counterB = registry.find("flex.schedule.task.executions")
                .tag("taskName", "taskB")
                .counter();

        assertNotNull(counterA);
        assertNotNull(counterB);
        assertEquals(1.0, counterA.count());
        assertEquals(1.0, counterB.count());
    }

    // ─── repeated execution + supplier re-registration safety ──────

    @Test
    void recordExecution_repeated_thousandsOfTimes_doesNotReRegisterMeters() {
        // The counter/timer caches must reuse the same meter across calls.
        // Calling recordExecution many times for the same (task, type, result) must
        // produce a single Counter instance — no "already registered" exceptions,
        // no duplicate meters in the registry.
        for (int i = 0; i < 5_000; i++) {
            recorder.recordExecution("hot", "FIXED_DELAY", Duration.ofMillis(10), true);
        }

        long counterCount = registry.getMeters().stream()
                .filter(m -> "flex.schedule.task.executions".equals(m.getId().getName()))
                .count();
        long timerCount = registry.getMeters().stream()
                .filter(m -> "flex.schedule.task.duration".equals(m.getId().getName()))
                .count();

        assertEquals(1, counterCount, "Counter must be cached, not re-registered per call");
        assertEquals(1, timerCount, "Timer must be cached, not re-registered per call");

        Counter counter = registry.find("flex.schedule.task.executions")
                .tag("taskName", "hot")
                .tag("taskType", "FIXED_DELAY")
                .tag("result", "success")
                .counter();
        assertEquals(5_000.0, counter.count());
    }

    @Test
    void setActiveTaskCountSupplier_calledTwice_firstSupplierWins() {
        // SimpleMeterRegistry de-duplicates meters by name; calling
        // setActiveTaskCountSupplier twice keeps the FIRST supplier.
        // This test pins that current behavior so any change is intentional.
        recorder.setActiveTaskCountSupplier(() -> 10);
        recorder.setActiveTaskCountSupplier(() -> 42);

        Gauge gauge = registry.find("flex.schedule.active.tasks").gauge();
        assertNotNull(gauge);
        assertEquals(10.0, gauge.value(),
            "First-registered supplier is retained on subsequent calls");
    }

    @Test
    void setActiveTaskCountSupplier_gaugeReflectsLiveChanges() {
        java.util.concurrent.atomic.AtomicInteger live = new java.util.concurrent.atomic.AtomicInteger(0);
        recorder.setActiveTaskCountSupplier(live::get);

        Gauge gauge = registry.find("flex.schedule.active.tasks").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value());

        live.set(7);
        assertEquals(7.0, gauge.value(),
            "Gauge must poll the supplier each time it's read, not capture a snapshot");
    }
}
