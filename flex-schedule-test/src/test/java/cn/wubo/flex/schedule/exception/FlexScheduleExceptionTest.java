package cn.wubo.flex.schedule.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlexScheduleExceptionTest {

    @Test
    void messageOnly_constructorKeepsMessage() {
        FlexScheduleException ex = new FlexScheduleException("m");
        assertThat(ex.getMessage()).isEqualTo("m");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    // ─── A11: cause-preserving constructor ────────────────────────────

    @Test
    void messageAndCause_constructorKeepsCause() {
        Throwable cause = new IllegalStateException("root");

        FlexScheduleException ex = new FlexScheduleException("m", cause);

        assertThat(ex.getMessage()).isEqualTo("m");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void nullCause_isAllowed() {
        FlexScheduleException ex = new FlexScheduleException("m", null);
        assertThat(ex.getCause()).isNull();
    }
}
