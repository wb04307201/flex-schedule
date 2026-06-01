package cn.wubo.flex.schedule.metrics;

import cn.wubo.flex.schedule.core.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Micrometer-based implementation of {@link MetricsRecorder}.
 * <p>
 * Registers the following metrics:
 * <ul>
 *   <li>{@code flex.schedule.task.executions} — counter with tags: taskName, taskType, result</li>
 *   <li>{@code flex.schedule.task.duration} — timer with tags: taskName, taskType</li>
 *   <li>{@code flex.schedule.active.tasks} — gauge for current active task count</li>
 * </ul>
 * <p>
 * Uses caching to avoid rebuilding Counter and Timer instances on every execution.
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public MicrometerMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordExecution(String taskName, String taskType, Duration duration, boolean success) {
        String counterKey = taskName + "|" + taskType + "|" + (success ? "success" : "failure");
        Counter counter = counterCache.computeIfAbsent(counterKey, k ->
                Counter.builder("flex.schedule.task.executions")
                        .tag("taskName", taskName)
                        .tag("taskType", taskType)
                        .tag("result", success ? "success" : "failure")
                        .register(meterRegistry));
        counter.increment();

        String timerKey = taskName + "|" + taskType;
        Timer timer = timerCache.computeIfAbsent(timerKey, k ->
                Timer.builder("flex.schedule.task.duration")
                        .tag("taskName", taskName)
                        .tag("taskType", taskType)
                        .register(meterRegistry));
        timer.record(duration);
    }

    @Override
    public void setActiveTaskCountSupplier(Supplier<Integer> supplier) {
        Gauge.builder("flex.schedule.active.tasks", supplier, s -> s.get().doubleValue())
                .description("Number of currently active flex scheduled tasks")
                .register(meterRegistry);
    }
}
