package cn.wubo.flex.schedule.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fast-fail validation tests for {@link RedisDistributedLock#tryLock(String, Duration)}.
 * <p>
 * These tests do not require a running Redis instance — they verify that
 * {@code tryLock} rejects null / zero / negative {@code lockDuration} BEFORE
 * touching the Redis template. We use a hand-rolled stub {@link StringRedisTemplate}
 * with a counting {@link ValueOperations} to prove that no Redis call was made.
 * <p>
 * Using a recording stub instead of Mockito keeps the redis module's test
 * dependency surface minimal (no mockito-core required).
 */
class RedisDistributedLockValidationTest {

    private RecordingRedisTemplate template;
    private RedisDistributedLock lock;

    @BeforeEach
    void setup() {
        template = new RecordingRedisTemplate();
        lock = new RedisDistributedLock(template);
    }

    @Test
    void tryLock_nullDuration_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> lock.tryLock("anyTask", null));
        assertEquals(0, template.opsForValueCallCount,
                "Redis must not be touched when lockDuration is null");
    }

    @Test
    void tryLock_zeroDuration_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> lock.tryLock("anyTask", Duration.ZERO));
        assertEquals(0, template.opsForValueCallCount,
                "Redis must not be touched when lockDuration is zero");
    }

    @Test
    void tryLock_negativeDuration_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> lock.tryLock("anyTask", Duration.ofSeconds(-1)));
        assertEquals(0, template.opsForValueCallCount,
                "Redis must not be touched when lockDuration is negative");
    }

    // ─── hand-rolled stub (no mockito) ────────────────────────────────

    private static class RecordingRedisTemplate extends StringRedisTemplate {
        int opsForValueCallCount = 0;

        @Override
        public ValueOperations<String, String> opsForValue() {
            opsForValueCallCount++;
            // Return null instead of throwing: a regression that skips the
            // validation guard will reach this point and trigger a downstream
            // NPE/IllegalArgumentException from the real setIfAbsent path,
            // while the per-test counter assertion remains meaningful.
            return null;
        }
    }
}
