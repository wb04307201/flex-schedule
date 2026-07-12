package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.Backoff;
import cn.wubo.flex.schedule.core.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    // ─── Constructor Validation ──────────────────────────────────────

    @Test
    void constructor_validParams_shouldCreate() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(5), Backoff.FIXED);
        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(5), policy.delay());
        assertEquals(Backoff.FIXED, policy.backoff());
    }

    @Test
    void constructor_maxAttemptsZero_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(0, Duration.ofSeconds(5), Backoff.FIXED));
    }

    @Test
    void constructor_maxAttemptsNegative_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(-1, Duration.ofSeconds(5), Backoff.FIXED));
    }

    @Test
    void constructor_nullDelay_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, null, Backoff.FIXED));
    }

    @Test
    void constructor_negativeDelay_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofSeconds(-1), Backoff.FIXED));
    }

    @Test
    void constructor_nullBackoff_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofSeconds(5), null));
    }

    // ─── Factory Methods ─────────────────────────────────────────────

    @Test
    void fixed_shouldCreateWithFixedBackoff() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(5));
        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(5), policy.delay());
        assertEquals(Backoff.FIXED, policy.backoff());
    }

    @Test
    void exponential_shouldCreateWithExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(2));
        assertEquals(5, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(2), policy.delay());
        assertEquals(Backoff.EXPONENTIAL, policy.backoff());
    }

    // ─── computeDelay ────────────────────────────────────────────────

    @Test
    void computeDelay_fixed_shouldReturnConstantDelay() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), policy.computeDelay(1));
        assertEquals(Duration.ofSeconds(5), policy.computeDelay(2));
        assertEquals(Duration.ofSeconds(5), policy.computeDelay(3));
    }

    @Test
    void computeDelay_exponential_shouldDoubleEachAttempt() {
        RetryPolicy policy = RetryPolicy.exponential(4, Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), policy.computeDelay(1));   // 1 * 2^0 = 1
        assertEquals(Duration.ofSeconds(2), policy.computeDelay(2));   // 1 * 2^1 = 2
        assertEquals(Duration.ofSeconds(4), policy.computeDelay(3));   // 1 * 2^2 = 4
        assertEquals(Duration.ofSeconds(8), policy.computeDelay(4));   // 1 * 2^3 = 8
    }

    @Test
    void computeDelay_exponentialFirstAttempt_shouldReturnBaseDelay() {
        RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(10), policy.computeDelay(1));
    }

    // ─── maxDelay cap + overflow guard ──────────────────────────────

    @Test
    void computeDelay_exponentialWithMaxDelay_capsAtMax() {
        RetryPolicy policy = RetryPolicy.exponential(10, Duration.ofSeconds(1), Duration.ofSeconds(30));
        // Attempt 1: 1s, 2: 2s, 3: 4s, 4: 8s, 5: 16s, 6: 32s → cap to 30s
        assertEquals(Duration.ofSeconds(1), policy.computeDelay(1));
        assertEquals(Duration.ofSeconds(16), policy.computeDelay(5));
        assertEquals(Duration.ofSeconds(30), policy.computeDelay(6));
        assertEquals(Duration.ofSeconds(30), policy.computeDelay(7));
    }

    @Test
    void computeDelay_exponentialAttemptOver30_returnsMaxDelay() {
        RetryPolicy policy = RetryPolicy.exponential(100, Duration.ofSeconds(1), Duration.ofMinutes(5));
        // attempt > 30 should short-circuit and return maxDelay to prevent overflow
        assertEquals(Duration.ofMinutes(5), policy.computeDelay(31));
        assertEquals(Duration.ofMinutes(5), policy.computeDelay(100));
    }

    @Test
    void computeDelay_exponentialWithoutMaxDelay_attemptOver30_returnsSentinel() {
        // Without maxDelay, attempt>30 returns Duration.ofDays(365) as overflow sentinel.
        RetryPolicy policy = new RetryPolicy(100, Duration.ofSeconds(1), Backoff.EXPONENTIAL, null);
        assertEquals(Duration.ofDays(365), policy.computeDelay(31));
    }

    @Test
    void exponential_withCustomMaxDelay_factory() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(10), policy.maxDelay());
        assertEquals(Duration.ofSeconds(1), policy.computeDelay(1));
    }

    @Test
    void fixed_withMaxDelay_factory() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(5), Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(1), policy.maxDelay());
        assertEquals(Duration.ofSeconds(5), policy.computeDelay(1)); // cap not applied (under cap)
    }

    @Test
    void constructor_maxDelayNegativeOrZero_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofSeconds(1), Backoff.FIXED, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofSeconds(1), Backoff.FIXED, Duration.ZERO));
    }
}
