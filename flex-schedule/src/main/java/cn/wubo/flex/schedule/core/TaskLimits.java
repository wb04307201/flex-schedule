package cn.wubo.flex.schedule.core;

import java.time.Duration;

/**
 * Immutable snapshot of configured task scheduling limits.
 *
 * @param minInterval minimum allowed interval for FIXED_DELAY / FIXED_RATE / ONE_SHOT (null = no limit)
 * @param maxLifetime maximum lifetime before auto-cancel for FIXED_DELAY / FIXED_RATE / CRON (null = no limit)
 * @param mode        enforcement mode (STRICT throws, WARN logs, OFF skips)
 */
public record TaskLimits(Duration minInterval, Duration maxLifetime, Mode mode) {

    /** Sentinel: no limits, no enforcement. Used by 2-arg constructor for backward compat. */
    public static final TaskLimits DISABLED = new TaskLimits(null, null, Mode.OFF);

    public boolean hasMinInterval() {
        return minInterval != null;
    }

    public boolean hasMaxLifetime() {
        return maxLifetime != null;
    }

    public boolean isEnforcing() {
        return mode != Mode.OFF;
    }

    public enum Mode { STRICT, WARN, OFF }
}