package cn.wubo.flex.schedule.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory implementation of {@link ExecutionHistory} using a bounded ring buffer per task.
 * Thread-safe via ConcurrentHashMap and ConcurrentLinkedDeque.
 */
public class InMemoryExecutionHistory implements ExecutionHistory {

    private final int maxRecordsPerTask;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<ExecutionRecord>> historyMap;

    /**
     * Creates an InMemoryExecutionHistory with a default capacity of 100 records per task.
     */
    public InMemoryExecutionHistory() {
        this(100);
    }

    /**
     * Creates an InMemoryExecutionHistory with a specified capacity per task.
     *
     * @param maxRecordsPerTask maximum number of records to keep per task
     */
    public InMemoryExecutionHistory(int maxRecordsPerTask) {
        if (maxRecordsPerTask < 1) {
            throw new IllegalArgumentException("maxRecordsPerTask must be >= 1");
        }
        this.maxRecordsPerTask = maxRecordsPerTask;
        this.historyMap = new ConcurrentHashMap<>();
    }

    @Override
    public void record(ExecutionRecord record) {
        ConcurrentLinkedDeque<ExecutionRecord> deque =
                historyMap.computeIfAbsent(record.taskName(), k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(record);
        // Trim to max size
        while (deque.size() > maxRecordsPerTask) {
            deque.pollLast();
        }
    }

    @Override
    public List<ExecutionRecord> getHistory(String taskName, int limit) {
        ConcurrentLinkedDeque<ExecutionRecord> deque = historyMap.get(taskName);
        if (deque == null || limit <= 0) {
            return List.of();
        }
        List<ExecutionRecord> result = new ArrayList<>(deque);
        return result.subList(0, Math.min(result.size(), limit));
    }

    @Override
    public List<ExecutionRecord> getAllHistory(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ExecutionRecord> all = new ArrayList<>();
        for (ConcurrentLinkedDeque<ExecutionRecord> deque : historyMap.values()) {
            all.addAll(deque);
        }
        all.sort(Comparator.comparing(ExecutionRecord::startTime).reversed());
        return all.subList(0, Math.min(all.size(), limit));
    }

    @Override
    public void clear(String taskName) {
        if (taskName == null) {
            historyMap.clear();
        } else {
            historyMap.remove(taskName);
        }
    }
}
