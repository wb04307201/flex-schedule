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
}
