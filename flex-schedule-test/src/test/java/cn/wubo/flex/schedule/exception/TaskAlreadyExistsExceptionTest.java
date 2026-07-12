package cn.wubo.flex.schedule.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskAlreadyExistsExceptionTest {

    @Test
    void message_format_includesTaskName() {
        TaskAlreadyExistsException ex = new TaskAlreadyExistsException("dailyReport");
        assertThat(ex.getMessage()).isEqualTo("Task [dailyReport] already exists, add failed");
    }

    @Test
    void isFlexScheduleException_andRuntimeException() {
        TaskAlreadyExistsException ex = new TaskAlreadyExistsException("foo");
        assertThat(ex).isInstanceOf(FlexScheduleException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}