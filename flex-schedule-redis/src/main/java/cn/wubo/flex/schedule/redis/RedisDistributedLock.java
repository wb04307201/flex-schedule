package cn.wubo.flex.schedule.redis;

import cn.wubo.flex.schedule.core.DistributedLock;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Redis-based implementation of {@link DistributedLock} using Redis SETNX.
 * <p>
 * This implementation uses Redis to coordinate distributed locks across multiple application instances.
 * Each lock is stored as a Redis key with an expiration time to prevent deadlocks.
 * </p>
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * @Bean
 * public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate) {
 *     return new RedisDistributedLock(redisTemplate);
 * }
 * }</pre>
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
