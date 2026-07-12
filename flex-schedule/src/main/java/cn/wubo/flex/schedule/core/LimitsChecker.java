package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.TaskLimitExceededException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * Encapsulates limit-enforcement decisions for the registrar.
 * Stateless except for the immutable TaskLimits snapshot.
 */
@Slf4j
final class LimitsChecker {

    private final TaskLimits limits;

    LimitsChecker(TaskLimits limits) {
        this.limits = limits != null ? limits : TaskLimits.DISABLED;
    }

    /**
     * Validates that {@code interval} meets the configured minimum.
     *
     * @throws TaskLimitExceededException if STRICT mode and interval is below min
     */
    void assertInterval(String taskName, Duration interval) {
        if (!limits.isEnforcing() || !limits.hasMinInterval()) return;
        if (interval.compareTo(limits.minInterval()) >= 0) return;

        switch (limits.mode()) {
            case STRICT -> throw new TaskLimitExceededException(
                "Task [%s] interval %s is below minimum %s"
                    .formatted(taskName, interval, limits.minInterval()));
            case WARN -> log.warn(
                "Task [{}] interval {} is below minimum {} — allowed due to mode=warn",
                taskName, interval, limits.minInterval());
            case OFF -> { /* unreachable: isEnforcing() returned false above */ }
        }
    }

    /**
     * Lazy lifetime check. Returns true iff the task has exceeded max-lifetime and should be cancelled.
     * WARN mode logs a warning and returns false (mirroring {@link #assertInterval}'s WARN semantics —
     * "log and allow"). STRICT mode logs INFO and returns true so the caller cancels.
     */
    boolean isExpired(String taskName, Instant createdAt) {
        if (!limits.isEnforcing() || !limits.hasMaxLifetime()) return false;
        Duration age = Duration.between(createdAt, Instant.now());
        if (age.compareTo(limits.maxLifetime()) < 0) return false;

        switch (limits.mode()) {
            case STRICT -> {
                log.info("Task [{}] exceeded max lifetime {} (age={}), auto-cancelling",
                         taskName, limits.maxLifetime(), age);
                return true;
            }
            case WARN -> {
                log.warn("Task [{}] exceeded max lifetime {} (age={}) — allowed due to mode=warn",
                         taskName, limits.maxLifetime(), age);
                return false;
            }
            case OFF -> { /* unreachable: isEnforcing() returned false above */ }
        }
        return false;
    }
}