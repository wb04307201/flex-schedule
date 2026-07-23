package cn.wubo.flex.schedule.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RedisDistributedLock#unlock} call-shape.
 * <p>
 * Unlike {@link RedisDistributedLockTest}, this class has NO Redis dependency
 * and runs in every build. It pins down the implementation contract that the
 * unlock path MUST use the atomic Lua script (via
 * {@code RedisTemplate.execute(RedisScript, ...)}) and MUST NOT do raw
 * {@code get()}+{@code delete()}.
 * </p>
 * <p>
 * This is the regression net for the original bug:
 * </p>
 * <pre>
 *   String currentValue = redisTemplate.opsForValue().get(lockKey);
 *   if (lockValue.equals(currentValue)) {
 *       redisTemplate.delete(lockKey);
 *   }
 * </pre>
 * <p>
 * The local-variable capture between {@code get()} and {@code delete()} left
 * a race window where a TTL-expiry successor's lock could be wiped. The Lua
 * CAS closes that window by doing read-and-delete server-side, atomically.
 * If anyone reverts to the {@code get}+{@code delete} pattern, this test
 * fails immediately on any machine &mdash; no Redis required.
 * </p>
 */
class RedisDistributedLockUnlockScriptTest {

    @Test
    void unlock_usesAtomicScript_neverRawGetThenDelete() {
        StringRedisTemplate mock = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        // Stub the opsForValue() handle so a buggy implementation that calls
        // get()/delete() through the old path doesn't blow up with NPE before
        // the assertions can run.
        when(mock.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("some-stale-or-current-value");

        // Stub the Lua script execute call so the implementation doesn't NPE.
        when(mock.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(),
                any(Object[].class)))
            .thenReturn(0L);

        RedisDistributedLock lock = new RedisDistributedLock(mock);
        lock.unlock("any-task");

        // The atomic fix routes through RedisTemplate.execute(RedisScript, ...).
        verify(mock, atLeastOnce()).execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            any(Object[].class));

        // The buggy fix used opsForValue().get(...) and .delete(...) separately.
        // Both must be absent from the new implementation.
        verify(mock, never()).delete((String) any());
        verify(valueOps, never()).get(any());
    }
}
