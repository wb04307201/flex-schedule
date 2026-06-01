package cn.wubo.flex.schedule.core;

import org.springframework.util.Assert;

import java.time.Duration;

/**
 * Immutable retry configuration for scheduled tasks.
 *
 * @param maxAttempts maximum number of retry attempts (must be >= 1)
 * @param delay       base delay between retries (must not be null or negative)
 * @param backoff     backoff strategy (must not be null)
 * @param maxDelay    maximum delay cap (null means no cap, useful for exponential backoff)
 */
public record RetryPolicy(int maxAttempts, Duration delay, Backoff backoff, Duration maxDelay) {

    /**
     * Default maximum delay: 1 hour.
     */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofHours(1);

    public RetryPolicy {
        Assert.isTrue(maxAttempts >= 1, "maxAttempts must be >= 1");
        Assert.notNull(delay, "delay must not be null");
        Assert.isTrue(!delay.isNegative(), "delay must not be negative");
        Assert.notNull(backoff, "backoff must not be null");
        // maxDelay can be null (no cap)
        if (maxDelay != null) {
            Assert.isTrue(!maxDelay.isNegative() && !maxDelay.isZero(),
                    "maxDelay must be positive if specified");
        }
    }

    /**
     * Creates a retry policy without a maximum delay cap.
     */
    public RetryPolicy(int maxAttempts, Duration delay, Backoff backoff) {
        this(maxAttempts, delay, backoff, null);
    }

    /**
     * Creates a fixed-delay retry policy.
     */
    public static RetryPolicy fixed(int maxAttempts, Duration delay) {
        return new RetryPolicy(maxAttempts, delay, Backoff.FIXED);
    }

    /**
     * Creates a fixed-delay retry policy with a maximum delay cap.
     */
    public static RetryPolicy fixed(int maxAttempts, Duration delay, Duration maxDelay) {
        return new RetryPolicy(maxAttempts, delay, Backoff.FIXED, maxDelay);
    }

    /**
     * Creates an exponential-backoff retry policy with the default max delay (1 hour).
     */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, Backoff.EXPONENTIAL, DEFAULT_MAX_DELAY);
    }

    /**
     * Creates an exponential-backoff retry policy with a custom maximum delay cap.
     */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, Backoff.EXPONENTIAL, maxDelay);
    }

    /**
     * Computes the delay for a given attempt number (1-based).
     * The result is capped at maxDelay if specified.
     */
    public Duration computeDelay(int attempt) {
        Duration computed = switch (backoff) {
            case FIXED -> delay;
            case EXPONENTIAL -> {
                // Prevent overflow for large attempt numbers
                if (attempt > 30) {
                    yield maxDelay != null ? maxDelay : Duration.ofDays(365);
                }
                long multiplier = (long) Math.pow(2, attempt - 1);
                yield delay.multipliedBy(multiplier);
            }
        };

        // Apply max delay cap if specified
        if (maxDelay != null && computed.compareTo(maxDelay) > 0) {
            return maxDelay;
        }
        return computed;
    }
}
