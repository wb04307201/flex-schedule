package cn.wubo.flex.schedule.core;

import java.time.Duration;

/**
 * Abstraction for distributed locking to support cluster-aware scheduling.
 * <p>
 * In a multi-instance deployment, only one node should execute a given task
 * at any time. Implementations of this interface provide the locking mechanism
 * using external stores (e.g., Redis, JDBC, ZooKeeper, ShedLock).
 * </p>
 * <p>
 * When no implementation is provided (or the {@link #NOOP} instance is used),
 * tasks execute on all nodes (no cluster coordination).
 * </p>
 *
 * <h2>Lifecycle and call sites</h2>
 * <p>
 * {@link FlexScheduledTaskRegistrar} acquires the lock before each scheduled
 * execution and releases it in a {@code finally} block, so the lock is always
 * released even when the task throws. The lock duration passed to
 * {@link #tryLock} defaults to the task's interval (for fixed-delay / fixed-rate)
 * or {@link Duration#ofSeconds(long) 30 seconds} for cron and one-shot tasks.
 * </p>
 *
 * <h2>Ownership semantics</h2>
 * <p>
 * Implementations MUST make {@link #unlock} safe to call when:
 * </p>
 * <ul>
 *   <li>The lock was never acquired.</li>
 *   <li>The lock was acquired by a different instance (ownership mismatch).</li>
 *   <li>The lock has already expired.</li>
 * </ul>
 * <p>
 * In each of these cases, {@code unlock} must be a silent no-op rather than
 * throwing, otherwise a single failed task can wedge the scheduler.
 * </p>
 *
 * <h2>Implementation guidance</h2>
 * <ul>
 *   <li>Use a per-instance identifier (e.g. UUID) as the lock value so that
 *       {@code unlock} can verify ownership before deleting.</li>
 *   <li>Always set a TTL on the lock key — a crashed instance's lock would
 *       otherwise block the task forever.</li>
 *   <li>{@link #tryLock} must be atomic and idempotent.</li>
 * </ul>
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@code NOOP} (in this interface) — always grants; no coordination.</li>
 *   <li>{@code RedisDistributedLock} (in the {@code flex-schedule-redis} module)
 *       — Redis SETNX-based, ownership-checked, TTL-expiring.</li>
 * </ul>
 *
 * <h2>Example: providing a custom implementation</h2>
 * <pre>{@code
 * @Bean
 * public DistributedLock myDistributedLock(StringRedisTemplate redisTemplate) {
 *     return new RedisDistributedLock(redisTemplate);
 * }
 * }</pre>
 *
 * <p>
 * Note: this is not a Redlock implementation. {@code RedisDistributedLock}
 * uses single-node SETNX with TTL, which is sufficient for "at most one
 * instance runs a task at a time" but does not provide the stronger Redlock
 * guarantees across multiple Redis nodes. Replace the bean if you need
 * Redlock-style safety.
 * </p>
 */
public interface DistributedLock {

    /**
     * Attempts to acquire a lock for the given task.
     * <p>
     * Returns {@code true} if the lock was acquired; {@code false} if another
     * node already holds it (in which case the caller should skip the
     * execution). Implementations must be idempotent and atomic.
     *
     * @param taskName     the task name to lock; unique per scheduled task
     * @param lockDuration how long the lock should be held before auto-expiring;
     *                     the caller releases it earlier via {@link #unlock}
     *                     whenever possible
     * @return {@code true} if the lock was acquired, {@code false} if another
     *         node holds it
     */
    boolean tryLock(String taskName, Duration lockDuration);

    /**
     * Releases the lock for the given task.
     * <p>
     * Must be safe to call in any of these conditions:
     * </p>
     * <ul>
     *   <li>The lock was never acquired (idempotent).</li>
     *   <li>The lock was acquired by a different instance (ownership check
     *       prevents accidental release).</li>
     *   <li>The lock has already expired (Redis returned no value).</li>
     * </ul>
     * <p>
     * Must not throw under any of these conditions.
     * </p>
     *
     * @param taskName the task name to unlock
     */
    void unlock(String taskName);

    /**
     * No-op implementation that always grants the lock.
     * <p>
     * Used as the default when no cluster coordination is configured; every
     * node runs every task.
     * </p>
     */
    DistributedLock NOOP = new DistributedLock() {
        @Override
        public boolean tryLock(String taskName, Duration lockDuration) {
            return true;
        }

        @Override
        public void unlock(String taskName) {
            // no-op
        }
    };
}