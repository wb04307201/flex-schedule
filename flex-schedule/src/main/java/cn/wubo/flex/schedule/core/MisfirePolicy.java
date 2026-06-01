package cn.wubo.flex.schedule.core;

/**
 * Defines the policy for handling missed task executions (misfires).
 * <p>
 * A misfire occurs when a task's scheduled execution time has passed
 * without the task being executed (e.g., due to scheduler downtime,
 * system overload, or thread pool exhaustion).
 * </p>
 */
public enum MisfirePolicy {

    /**
     * Fire the task immediately to catch up on missed executions.
     * After firing, resume the normal schedule.
     */
    FIRE_IMMEDIATELY,

    /**
     * Skip the missed execution and wait for the next scheduled time.
     * This is the default policy.
     */
    SKIP,

    /**
     * Fire the task once immediately if missed, then continue with the normal schedule.
     * Similar to FIRE_IMMEDIATELY but ensures only one catch-up execution.
     */
    FIRE_ONCE
}
