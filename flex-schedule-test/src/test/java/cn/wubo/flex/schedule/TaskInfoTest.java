package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.TaskInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskInfoTest {

    @Test
    void shouldCreateRecord() {
        TaskInfo info = new TaskInfo("myTask", "CRON", "0 * * * * *");
        assertEquals("myTask", info.taskName());
        assertEquals("CRON", info.taskType());
        assertEquals("0 * * * * *", info.schedule());
    }

    @Test
    void equals_shouldWorkCorrectly() {
        TaskInfo a = new TaskInfo("task", "CRON", "* * * * * *");
        TaskInfo b = new TaskInfo("task", "CRON", "* * * * * *");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentValues_shouldNotBeEqual() {
        TaskInfo a = new TaskInfo("task1", "CRON", "* * * * * *");
        TaskInfo b = new TaskInfo("task2", "CRON", "* * * * * *");
        assertNotEquals(a, b);
    }

    @Test
    void toString_shouldContainFields() {
        TaskInfo info = new TaskInfo("myTask", "FIXED_RATE", "10s/0s");
        String str = info.toString();
        assertTrue(str.contains("myTask"));
        assertTrue(str.contains("FIXED_RATE"));
        assertTrue(str.contains("10s/0s"));
    }
}
