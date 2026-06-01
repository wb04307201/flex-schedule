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
 * When no implementation is provided (or the NOOP instance is used),
 * tasks execute on all nodes (no cluster coordination).
 * </p>
 *
 * <h3>Usage with ShedLock:</h3>
 * <pre>{@code
 * // In your Spring configuration:
 * @Bean
 * public DistributedLock shedLockDistributedLock(LockProvider lockProvider) {
 *     return new ShedLockDistributedLock(lockProvider);
 * }
 * }</pre>
 *
 * <h3>Usage with Redis:</h3>
 * <pre>{@code
 * @Bean
 * public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
 *     return new RedisDistributedLock(redisTemplate);
 * }
 * }</pre>
 */
public interface DistributedLock {

    /**
     * Attempts to acquire a lock for the given task.
     *
     * @param taskName     the task name to lock
     * @param lockDuration how long the lock should be held
     * @return {@code true} if the lock was acquired, {@code false} if another node holds it
     */
    boolean tryLock(String taskName, Duration lockDuration);

    /**
     * Releases the lock for the given task.
     *
     * @param taskName the task name to unlock
     */
    void unlock(String taskName);

    /**
     * No-op implementation that always grants the lock (no cluster coordination).
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
