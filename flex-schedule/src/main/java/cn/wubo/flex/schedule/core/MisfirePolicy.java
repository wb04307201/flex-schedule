package cn.wubo.flex.schedule.core;

/**
 * Defines the policy for handling missed task executions (misfires).
 * <p>
 * A misfire occurs when a task's scheduled execution time has passed
 * without the task being executed (e.g., due to scheduler downtime,
 * system overload, or thread pool exhaustion).
 * </p>
 * <p>
 * The policy applies to <b>recurring task types only</b> (CRON, FIXED_DELAY,
 * FIXED_RATE). One-shot tasks have no schedule to misfire against and ignore
 * this setting entirely.
 * </p>
 * <p>
 * A fire is considered late when its actual execution time exceeds the
 * scheduled time plus the task's {@code misfireThreshold}. Semantics:
 * </p>
 * <table>
 *   <caption>Misfire policy semantics</caption>
 *   <tr><th>Scenario</th><th>SKIP</th><th>FIRE_ONCE</th><th>FIRE_IMMEDIATELY</th></tr>
 *   <tr>
 *     <td>Runtime lateness &gt; threshold</td>
 *     <td>Skips this execution</td>
 *     <td>Executes as normal</td>
 *     <td>Executes as normal</td>
 *   </tr>
 *   <tr>
 *     <td>Pause-gap catch-up on resume</td>
 *     <td>No catch-up fires</td>
 *     <td>One catch-up fire if the gap missed at least one firing, none otherwise</td>
 *     <td>{@code min(missed, 10)} catch-up fires</td>
 *   </tr>
 * </table>
 * <p>
 * Note: at runtime, {@link #FIRE_ONCE} and {@link #FIRE_IMMEDIATELY} behave
 * identically for late fires; they differ only in how many executions are
 * replayed when catching up after a pause.
 * </p>
 */
public enum MisfirePolicy {

    /**
     * Fire the task immediately to catch up on missed executions
     * (up to a bounded number of replays on resume), then resume the
     * normal schedule.
     */
    FIRE_IMMEDIATELY,

    /**
     * Skip the missed execution and wait for the next scheduled time.
     */
    SKIP,

    /**
     * Fire the task once immediately if missed, then continue with the normal
     * schedule. Similar to {@link #FIRE_IMMEDIATELY} but ensures only one
     * catch-up execution. This is the default policy.
     */
    FIRE_ONCE
}
