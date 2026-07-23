package cn.wubo.flex.schedule.redis;

import cn.wubo.flex.schedule.core.DistributedLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
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
 * <h2>Atomic release</h2>
 * <p>
 * {@link #unlock} uses a server-side Lua compare-and-delete script so the
 * ownership check and the delete happen in a single Redis round-trip. This
 * closes the race window in a naive {@code GET}-then-{@code DEL} implementation,
 * where the TTL could expire and a successor instance could acquire the lock
 * between the local value capture and the subsequent delete — causing the
 * successor's lock to be wiped by the original owner.
 * </p>
 * <p>
 * The script returns {@code 1} when the value matches and the key was deleted,
 * {@code 0} otherwise. The Java side treats both outcomes as success: the
 * contract is "do nothing if you don't own the lock", and the Lua script
 * enforces that contract atomically.
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

    /**
     * Server-side compare-and-delete: only delete the key when its current
     * value equals the caller-supplied expected value. KEYS[1] is the lock
     * key; ARGV[1] is the expected owner UUID.
     * <p>
     * Runs atomically inside the Redis server, so no other client can change
     * the value between the read and the delete.
     */
    private static final String UNLOCK_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('DEL', KEYS[1]) "
            + "else "
            + "return 0 "
            + "end";

    private static final RedisScript<Long> UNLOCK_SCRIPT =
        new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

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
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "lockDuration must be positive, got " + lockDuration);
        }
        String lockKey = LOCK_KEY_PREFIX + taskName;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, lockDuration);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void unlock(String taskName) {
        String lockKey = LOCK_KEY_PREFIX + taskName;
        // Atomic compare-and-delete: the Lua script runs GET + DEL on the
        // server in one shot. If the key is missing or held by a different
        // owner, the script returns 0 and we do nothing. If we still own
        // it, the script deletes it. This eliminates the GET-then-DEL race
        // where a TTL-expiry successor's lock could be wiped.
        redisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(lockKey),
            lockValue);
    }
}
