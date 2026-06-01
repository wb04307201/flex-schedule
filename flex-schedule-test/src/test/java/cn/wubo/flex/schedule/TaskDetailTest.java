package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.Backoff;
import cn.wubo.flex.schedule.core.RetryPolicy;
import cn.wubo.flex.schedule.core.TaskDetail;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TaskDetailTest {

    @Test
    void shouldCreateRecord() {
        Instant now = Instant.now();
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(5));
        TaskDetail detail = new TaskDetail("task1", "CRON", "0 * * * * *", false, policy, now, false);

        assertEquals("task1", detail.taskName());
        assertEquals("CRON", detail.taskType());
        assertEquals("0 * * * * *", detail.schedule());
        assertFalse(detail.oneShot());
        assertEquals(policy, detail.retryPolicy());
        assertEquals(now, detail.createdAt());
        assertFalse(detail.paused());
    }

    @Test
    void equals_shouldWorkCorrectly() {
        Instant now = Instant.now();
        TaskDetail detail1 = new TaskDetail("task1", "CRON", "0 * * * * *", false, null, now, false);
        TaskDetail detail2 = new TaskDetail("task1", "CRON", "0 * * * * *", false, null, now, false);

        assertEquals(detail1, detail2);
        assertEquals(detail1.hashCode(), detail2.hashCode());
    }

    @Test
    void equals_differentValues_shouldNotBeEqual() {
        Instant now = Instant.now();
        TaskDetail detail1 = new TaskDetail("task1", "CRON", "0 * * * * *", false, null, now, false);
        TaskDetail detail2 = new TaskDetail("task2", "CRON", "0 * * * * *", false, null, now, false);

        assertNotEquals(detail1, detail2);
    }

    @Test
    void oneShot_shouldBeTrue() {
        Instant now = Instant.now();
        TaskDetail detail = new TaskDetail("oneShot", "ONE_SHOT", "delay=PT5S", true, null, now, false);

        assertTrue(detail.oneShot());
        assertEquals("ONE_SHOT", detail.taskType());
    }

    @Test
    void retryPolicy_shouldBeStored() {
        Instant now = Instant.now();
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(2));
        TaskDetail detail = new TaskDetail("retry", "FIXED_DELAY", "10s/0s", false, policy, now, false);

        assertNotNull(detail.retryPolicy());
        assertEquals(5, detail.retryPolicy().maxAttempts());
        assertEquals(Backoff.EXPONENTIAL, detail.retryPolicy().backoff());
    }

    @Test
    void paused_shouldBeStored() {
        Instant now = Instant.now();
        TaskDetail detail = new TaskDetail("paused", "CRON", "0 * * * * *", false, null, now, true);

        assertTrue(detail.paused());
    }

    @Test
    void toString_shouldContainFields() {
        Instant now = Instant.now();
        TaskDetail detail = new TaskDetail("task1", "CRON", "0 * * * * *", false, null, now, false);

        String str = detail.toString();
        assertTrue(str.contains("task1"));
        assertTrue(str.contains("CRON"));
        assertTrue(str.contains("0 * * * * *"));
    }
}
