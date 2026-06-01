package cn.wubo.flex.schedule.core;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link FlexScheduledTaskService}.
 * <p>
 * All operations delegate to {@link FlexScheduledTaskRegistrar}.
 * </p>
 */
public class DefaultFlexScheduledTaskService implements FlexScheduledTaskService {

    private final FlexScheduledTaskRegistrar flexScheduledTaskRegistrar;

    public DefaultFlexScheduledTaskService(FlexScheduledTaskRegistrar flexScheduledTaskRegistrar) {
        this.flexScheduledTaskRegistrar = flexScheduledTaskRegistrar;
    }

    // ─── Fluent Builder ───────────────────────────────────────────────

    @Override
    public TaskBuilder task(String taskName) {
        return new TaskBuilder(this, taskName);
    }

    // ─── Cron Tasks ──────────────────────────────────────────────────

    @Override
    public void add(String taskName, String cron, Runnable runnable) {
        flexScheduledTaskRegistrar.addCronTask(taskName, cron, runnable);
    }

    @Override
    public void add(String taskName, String cron, ZoneId zoneId, Runnable runnable) {
        flexScheduledTaskRegistrar.addCronTask(taskName, cron, zoneId, runnable);
    }

    @Override
    public void add(String taskName, String cron, Duration timeout, Runnable runnable) {
        flexScheduledTaskRegistrar.addCronTask(taskName, cron, timeout, runnable);
    }

    @Override
    public void add(String taskName, String cron, Runnable runnable, RetryPolicy retryPolicy) {
        flexScheduledTaskRegistrar.addCronTask(taskName, cron, runnable, retryPolicy);
    }

    // ─── Fixed Delay Tasks ────────────────────────────────────────────

    @Override
    public void addFixedDelayTask(String taskName, long interval, long initialDelay, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedDelayTask(taskName, interval, initialDelay, runnable);
    }

    @Override
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedDelayTask(taskName, interval, initialDelay, runnable);
    }

    @Override
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                                  Duration timeout, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedDelayTask(taskName, interval, initialDelay, timeout, runnable);
    }

    @Override
    public void addFixedDelayTask(String taskName, Duration interval, Duration initialDelay,
                                  Runnable runnable, RetryPolicy retryPolicy) {
        flexScheduledTaskRegistrar.addFixedDelayTask(taskName, interval, initialDelay, runnable, retryPolicy);
    }

    // ─── Fixed Rate Tasks ─────────────────────────────────────────────

    @Override
    public void addFixedRateTask(String taskName, long interval, long initialDelay, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedRateTask(taskName, interval, initialDelay, runnable);
    }

    @Override
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedRateTask(taskName, interval, initialDelay, runnable);
    }

    @Override
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                                 Duration timeout, Runnable runnable) {
        flexScheduledTaskRegistrar.addFixedRateTask(taskName, interval, initialDelay, timeout, runnable);
    }

    @Override
    public void addFixedRateTask(String taskName, Duration interval, Duration initialDelay,
                                 Runnable runnable, RetryPolicy retryPolicy) {
        flexScheduledTaskRegistrar.addFixedRateTask(taskName, interval, initialDelay, runnable, retryPolicy);
    }

    // ─── One-Shot Tasks ───────────────────────────────────────────────

    @Override
    public void schedule(String taskName, Duration delay, Runnable runnable) {
        flexScheduledTaskRegistrar.schedule(taskName, delay, runnable);
    }

    // ─── Replace / AddOrUpdate ────────────────────────────────────────

    @Override
    public boolean replaceCronTask(String taskName, String cron, Runnable runnable) {
        return flexScheduledTaskRegistrar.replaceCronTask(taskName, cron, runnable);
    }

    @Override
    public boolean replaceFixedDelayTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        return flexScheduledTaskRegistrar.replaceFixedDelayTask(taskName, interval, initialDelay, runnable);
    }

    @Override
    public boolean replaceFixedRateTask(String taskName, Duration interval, Duration initialDelay, Runnable runnable) {
        return flexScheduledTaskRegistrar.replaceFixedRateTask(taskName, interval, initialDelay, runnable);
    }

    // ─── Task Lifecycle ───────────────────────────────────────────────

    @Override
    public void cancel(String taskName) {
        flexScheduledTaskRegistrar.cancel(taskName);
    }

    @Override
    public void pause(String taskName) {
        flexScheduledTaskRegistrar.pause(taskName);
    }

    @Override
    public void resume(String taskName) {
        flexScheduledTaskRegistrar.resume(taskName);
    }

    @Override
    public boolean isPaused(String taskName) {
        return flexScheduledTaskRegistrar.isPaused(taskName);
    }

    // ─── Query ────────────────────────────────────────────────────────

    @Override
    public boolean exists(String taskName) {
        return flexScheduledTaskRegistrar.exists(taskName);
    }

    @Override
    public List<TaskInfo> listTasks() {
        return flexScheduledTaskRegistrar.listTasks();
    }

    @Override
    public Optional<TaskDetail> getTaskDetail(String taskName) {
        return flexScheduledTaskRegistrar.getTaskDetail(taskName);
    }

    // ─── Listeners ────────────────────────────────────────────────────

    @Override
    public void addListener(TaskExecutionListener listener) {
        flexScheduledTaskRegistrar.addListener(listener);
    }

    @Override
    public void removeListener(TaskExecutionListener listener) {
        flexScheduledTaskRegistrar.removeListener(listener);
    }

    // ─── Execution History ────────────────────────────────────────────

    @Override
    public void setExecutionHistory(ExecutionHistory executionHistory) {
        flexScheduledTaskRegistrar.setExecutionHistory(executionHistory);
    }

    @Override
    public List<ExecutionRecord> getExecutionHistory(String taskName, int limit) {
        return flexScheduledTaskRegistrar.getExecutionHistory().getHistory(taskName, limit);
    }

    @Override
    public List<ExecutionRecord> getAllExecutionHistory(int limit) {
        return flexScheduledTaskRegistrar.getExecutionHistory().getAllHistory(limit);
    }

    @Override
    public void clearExecutionHistory(String taskName) {
        flexScheduledTaskRegistrar.getExecutionHistory().clear(taskName);
    }

    // ─── Statistics ───────────────────────────────────────────────────

    @Override
    public Optional<TaskStatistics> getTaskStatistics(String taskName) {
        List<ExecutionRecord> records = flexScheduledTaskRegistrar.getExecutionHistory().getHistory(taskName, 1000);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TaskStatistics.fromRecords(taskName, records));
    }

    @Override
    public List<TaskStatistics> getAllTaskStatistics() {
        List<TaskInfo> tasks = flexScheduledTaskRegistrar.listTasks();
        return tasks.stream()
                .map(task -> {
                    List<ExecutionRecord> records = flexScheduledTaskRegistrar.getExecutionHistory().getHistory(task.taskName(), 1000);
                    return TaskStatistics.fromRecords(task.taskName(), records);
                })
                .toList();
    }

    // ─── Distributed Lock ─────────────────────────────────────────────

    @Override
    public void setDistributedLock(DistributedLock distributedLock) {
        flexScheduledTaskRegistrar.setDistributedLock(distributedLock);
    }
}
