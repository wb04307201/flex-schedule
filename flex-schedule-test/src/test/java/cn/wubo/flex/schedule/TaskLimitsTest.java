package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.TaskLimits;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TaskLimitsTest {

    @Test
    void DISABLED_hasNoLimitsAndIsNotEnforcing() {
        TaskLimits limits = TaskLimits.DISABLED;
        assertFalse(limits.hasMinInterval());
        assertFalse(limits.hasMaxLifetime());
        assertFalse(limits.isEnforcing());
    }

    @Test
    void hasMinInterval_trueWhenSet() {
        TaskLimits limits = new TaskLimits(Duration.ofMinutes(10), null, TaskLimits.Mode.STRICT);
        assertTrue(limits.hasMinInterval());
        assertFalse(limits.hasMaxLifetime());
        assertTrue(limits.isEnforcing());
    }

    @Test
    void hasMaxLifetime_trueWhenSet() {
        TaskLimits limits = new TaskLimits(null, Duration.ofDays(7), TaskLimits.Mode.STRICT);
        assertFalse(limits.hasMinInterval());
        assertTrue(limits.hasMaxLifetime());
    }

    @Test
    void offMode_isNotEnforcing_evenWithLimitsSet() {
        TaskLimits limits = new TaskLimits(Duration.ofMinutes(10), Duration.ofDays(7), TaskLimits.Mode.OFF);
        assertTrue(limits.hasMinInterval());
        assertTrue(limits.hasMaxLifetime());
        assertFalse(limits.isEnforcing());
    }
}