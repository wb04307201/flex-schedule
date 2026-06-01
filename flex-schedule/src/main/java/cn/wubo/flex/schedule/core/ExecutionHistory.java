package cn.wubo.flex.schedule.core;

import java.util.List;

/**
 * Stores and retrieves task execution history.
 * <p>
 * Implementations should be thread-safe. The default implementation
 * ({@link InMemoryExecutionHistory}) stores records in a bounded ring buffer.
 * </p>
 */
public interface ExecutionHistory {

    /**
     * Records a task execution.
     *
     * @param record the execution record
     */
    void record(ExecutionRecord record);

    /**
     * Returns the execution history for a specific task.
     *
     * @param taskName the task name
     * @param limit    maximum number of records to return
     * @return list of execution records, most recent first
     */
    List<ExecutionRecord> getHistory(String taskName, int limit);

    /**
     * Returns all execution history across all tasks.
     *
     * @param limit maximum number of records to return
     * @return list of execution records, most recent first
     */
    List<ExecutionRecord> getAllHistory(int limit);

    /**
     * Clears history for a specific task, or all tasks if taskName is null.
     *
     * @param taskName the task name (null to clear all)
     */
    void clear(String taskName);

    /**
     * No-op implementation that discards all records.
     */
    ExecutionHistory NOOP = new ExecutionHistory() {
        @Override public void record(ExecutionRecord record) {}
        @Override public List<ExecutionRecord> getHistory(String taskName, int limit) { return List.of(); }
        @Override public List<ExecutionRecord> getAllHistory(int limit) { return List.of(); }
        @Override public void clear(String taskName) {}
    };
}
