package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.autoconfigure.FlexScheduleProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlexSchedulePropertiesTest {

    @Test
    void defaultValues_shouldBeCorrect() {
        FlexScheduleProperties props = new FlexScheduleProperties();

        assertTrue(props.getEnabled());
        assertEquals(16, props.getPoolSize());
        assertEquals("FlexScheduleThreadPool-", props.getThreadNamePrefix());
        assertTrue(props.getRemoveOnCancel());
        assertEquals(30L, props.getAwaitTerminationSeconds());
    }

    @Test
    void setters_shouldWork() {
        FlexScheduleProperties props = new FlexScheduleProperties();

        props.setEnabled(false);
        props.setPoolSize(32);
        props.setThreadNamePrefix("Custom-");
        props.setRemoveOnCancel(false);
        props.setAwaitTerminationSeconds(60L);

        assertFalse(props.getEnabled());
        assertEquals(32, props.getPoolSize());
        assertEquals("Custom-", props.getThreadNamePrefix());
        assertFalse(props.getRemoveOnCancel());
        assertEquals(60L, props.getAwaitTerminationSeconds());
    }
}
