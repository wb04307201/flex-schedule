package cn.wubo.flex.schedule.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskLimitExceededExceptionTest {

    @Test
    void isRuntimeException() {
        // It must be unchecked so it propagates from registrar methods without forcing throws clauses.
        TaskLimitExceededException ex = new TaskLimitExceededException("boom");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void isFlexScheduleException() {
        // Project-wide base class so callers can catch it alongside other framework errors.
        TaskLimitExceededException ex = new TaskLimitExceededException("boom");
        assertThat(ex).isInstanceOf(FlexScheduleException.class);
    }

    @Test
    void message_isPreserved() {
        TaskLimitExceededException ex = new TaskLimitExceededException("Task [foo] interval 5s is below minimum 10m");
        assertThat(ex.getMessage()).isEqualTo("Task [foo] interval 5s is below minimum 10m");
    }
}