package cn.wubo.flex.schedule.redis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RedisDistributedLock#tryLock} call-shape.
 * <p>
 * Like {@link RedisDistributedLockUnlockScriptTest}, this class has NO Redis
 * dependency and runs in every build. It pins down the acquire contract that
 * the gated real-Redis suite ({@link RedisDistributedLockTest}) relies on:
 * </p>
 * <ul>
 *   <li>the key is {@code flex-schedule:lock:<taskName>};</li>
 *   <li>the value is the per-instance UUID, identical to the one the unlock
 *       path passes to the Lua script (so a successful acquire is always
 *       releasable by the same instance);</li>
 *   <li>the TTL is the exact {@code lockDuration} handed to {@code tryLock};</li>
 *   <li>the return value maps {@code TRUE → true} and {@code false/null → false}.</li>
 * </ul>
 * <p>
 * <strong>Divergence note (semantics of the reentrancy scenario):</strong>
 * the implementation keeps NO local ownership cache. A second
 * {@code tryLock} by the same instance on the same task DOES reach Redis
 * again — it is rejected by SETNX because the key still exists (holding this
 * same instance's UUID), not by any short-circuit in Java. The fake below
 * mirrors that server-side behavior so the always-on test stays consistent
 * with the gated real-Redis assertion
 * {@code tryLock_alreadyOwnedBySameInstance_returnsFalse}.
 * </p>
 */
class RedisDistributedLockTryLockShapeTest {

    private static final String TASK_NAME = "nightly-report";
    private static final String EXPECTED_KEY = "flex-schedule:lock:" + TASK_NAME;

    // ─── scenario 1: key / value / TTL shape ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tryLock_callsSetIfAbsentWithPrefixedKeyAndTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        // Stub the unlock path so we can read the same UUID from the Lua args.
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(0L);

        Duration ttl = Duration.ofSeconds(45);
        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertTrue(lock.tryLock(TASK_NAME, ttl));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).setIfAbsent(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertEquals(EXPECTED_KEY, keyCaptor.getValue(),
            "tryLock must write exactly the flex-schedule:lock:<taskName> key");
        assertEquals(ttl, ttlCaptor.getValue(),
            "tryLock must pass the caller's lockDuration through as the TTL");

        // The value must be a well-formed UUID (per-instance identity)...
        String acquireValue = valueCaptor.getValue();
        assertDoesNotThrow(() -> UUID.fromString(acquireValue),
            "tryLock value must be the UUID generated at construction time");

        // ...and it must be the SAME value the unlock path hands to the Lua
        // script — otherwise unlock could never release this instance's lock.
        lock.unlock(TASK_NAME);
        ArgumentCaptor<List> unlockKeys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> unlockValue = ArgumentCaptor.forClass(Object.class);
        verify(template).execute(any(RedisScript.class), unlockKeys.capture(), unlockValue.capture());
        assertEquals(List.of(EXPECTED_KEY), unlockKeys.getValue(),
            "unlock must target the same prefixed key tryLock wrote");
        assertEquals(acquireValue, unlockValue.getValue(),
            "unlock must present the same UUID value tryLock stored");
    }

    // ─── scenario 2: return mapping ──────────────────────────────────

    @Test
    void tryLock_trueWhenSetIfAbsentTrue() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertTrue(lock.tryLock(TASK_NAME, Duration.ofSeconds(10)),
            "setIfAbsent TRUE must map to tryLock true");
    }

    @Test
    void tryLock_falseWhenSetIfAbsentFalse() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertFalse(lock.tryLock(TASK_NAME, Duration.ofSeconds(10)),
            "setIfAbsent FALSE must map to tryLock false");
    }

    @Test
    void tryLock_falseWhenSetIfAbsentNull() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        // Pipeline/transaction mode can return null; tryLock must not NPE.
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertFalse(lock.tryLock(TASK_NAME, Duration.ofSeconds(10)),
            "setIfAbsent NULL must map to tryLock false (null-safe)");
    }

    // ─── scenario 3: same-instance reentrancy (source semantics) ─────

    @Test
    void tryLock_reentrantSameInstance_stillHitsRedisAndReturnsFalse() {
        // Stateful fake emulating Redis SETNX: acquire wins only while the
        // key is absent. No local ownership cache exists in the product, so
        // the second attempt must produce a SECOND Redis round-trip.
        Map<String, String> fakeStore = new HashMap<>();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
            .thenAnswer(invocation ->
                fakeStore.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);

        Duration ttl = Duration.ofSeconds(10);
        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertTrue(lock.tryLock(TASK_NAME, ttl), "first acquire must win");
        assertFalse(lock.tryLock(TASK_NAME, ttl),
            "same-instance re-acquire must fail while the key is held");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).setIfAbsent(keyCaptor.capture(), valueCaptor.capture(), any(Duration.class));
        // Both attempts went to Redis (no Java-side short-circuit) and both
        // presented the same per-instance UUID — SETNX, not identity tracking,
        // is what denies the second attempt.
        assertEquals(List.of(EXPECTED_KEY, EXPECTED_KEY), keyCaptor.getAllValues());
        assertEquals(List.of(valueCaptor.getAllValues().getFirst(),
                             valueCaptor.getAllValues().getFirst()), valueCaptor.getAllValues(),
            "repeated tryLock by one instance must reuse its construction-time UUID");
        assertEquals(1, fakeStore.size(), "only the held key must exist in the store");
    }

    @Test
    void tryLock_differentTaskNames_doNotBlockEachOther() {
        // Mirrors the gated real-Redis assertion tryLock_differentTasks_bothSucceed.
        Map<String, String> fakeStore = new HashMap<>();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = stubValueOps(template);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
            .thenAnswer(invocation ->
                fakeStore.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);

        Duration ttl = Duration.ofSeconds(10);
        RedisDistributedLock lock = new RedisDistributedLock(template);
        assertTrue(lock.tryLock("task-A", ttl));
        assertTrue(lock.tryLock("task-B", ttl),
            "independent task names must map to independent lock keys");
        assertTrue(fakeStore.containsKey("flex-schedule:lock:task-A"));
        assertTrue(fakeStore.containsKey("flex-schedule:lock:task-B"));
    }

    // ─── helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> stubValueOps(StringRedisTemplate template) {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        return valueOps;
    }
}
