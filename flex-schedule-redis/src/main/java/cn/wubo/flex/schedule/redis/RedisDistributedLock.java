package cn.wubo.flex.schedule.redis;

import cn.wubo.flex.schedule.core.DistributedLock;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Redis-based implementation of {@link DistributedLock} using Redis SETNX.
 * <p>
 * Coordinates task execution across multiple application instances: the first
 * instance to call {@link #tryLock} wins and runs the task; subsequent calls
 * (from this or any other instance) return {@code false} until the TTL expires
 * or the owner releases the lock.
 * </p>
 *
 * <h2>Storage format</h2>
 * <ul>
 *   <li>Key: {@code flex-schedule:lock:<taskName>}</li>
 *   <li>Value: a per-instance UUID generated at construction time</li>
 *   <li>TTL: the {@code lockDuration} passed to {@link #tryLock}</li>
 * </ul>
 * <p>
 * The UUID value lets {@link #unlock} verify ownership before deleting, so a
 * lock that has already expired or been stolen by another instance is not
 * accidentally released.
 * </p>
 *
 * <h2>Auto-configuration</h2>
 * <p>
 * When both {@code flex-schedule-spring-boot-starter} and {@code flex-schedule-redis}
 * are on the classpath, Spring Boot auto-configuration wires this lock into
 * {@code FlexScheduledTaskRegistrar}. Provide your own {@code DistributedLock}
 * bean to override.
 * </p>
 *
 * <h2>Not Redlock</h2>
 * <p>
 * This is single-node SETNX with TTL — sufficient for "at most one instance
 * runs a task at a time" but not for the stronger multi-node Redlock
 * guarantees. If you need Redlock, supply your own implementation.
 * </p>
 *
 * <p>
 * See {@code flex-schedule-redis/README.md} for usage and configuration details.
 * </p>
 */
public class RedisDistributedLock implements DistributedLock {

    private static final String LOCK_KEY_PREFIX = "flex-schedule:lock:";
    private final StringRedisTemplate redisTemplate;
    private final String lockValue;

    /**
     * Creates a RedisDistributedLock with a unique identifier for this instance.
     *
     * @param redisTemplate the Spring Data Redis template
     */
    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.lockValue = UUID.randomUUID().toString();
    }

    @Override
    public boolean tryLock(String taskName, Duration lockDuration) {
        String lockKey = LOCK_KEY_PREFIX + taskName;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, lockDuration);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void unlock(String taskName) {
        String lockKey = LOCK_KEY_PREFIX + taskName;
        // Only delete if we own the lock (value matches)
        String currentValue = redisTemplate.opsForValue().get(lockKey);
        if (lockValue.equals(currentValue)) {
            redisTemplate.delete(lockKey);
        }
    }
}
