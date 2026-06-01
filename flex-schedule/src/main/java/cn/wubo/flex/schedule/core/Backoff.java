package cn.wubo.flex.schedule.core;

/**
 * Backoff strategy for retry delays.
 */
public enum Backoff {
    /** Constant delay between retries. */
    FIXED,
    /** Delay doubles with each attempt: delay * 2^(attempt-1). */
    EXPONENTIAL
}
