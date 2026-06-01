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
}
