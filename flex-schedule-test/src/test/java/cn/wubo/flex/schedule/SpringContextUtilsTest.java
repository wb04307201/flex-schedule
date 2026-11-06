package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.SpringContextUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class SpringContextUtilsTest {

    @Configuration
    static class TestConfig {
        @Bean
        public SpringContextUtils springContextUtils() {
            return new SpringContextUtils();
        }

        @Bean
        public String testBean() {
            return "hello";
        }
    }

    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void initContext() throws Exception {
        // Reset the static field to avoid stale references from other test classes
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);

        context = new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void getBean_afterContextInit_returnsBean() {
        Object bean = SpringContextUtils.getBean("testBean");
        assertNotNull(bean);
        assertEquals("hello", bean);
    }

    @Test
    void getBean_byClass_shouldWork() {
        String bean = SpringContextUtils.getBean(String.class);
        assertEquals("hello", bean);
    }

    @Test
    void getBean_byNameAndClass_shouldWork() {
        String bean = SpringContextUtils.getBean("testBean", String.class);
        assertEquals("hello", bean);
    }

    @Test
    void containsBean_existing_shouldReturnTrue() {
        assertTrue(SpringContextUtils.containsBean("testBean"));
    }

    @Test
    void containsBean_nonExisting_shouldReturnFalse() {
        assertFalse(SpringContextUtils.containsBean("nonExistent"));
    }

    @Test
    void isSingleton_shouldWork() {
        assertTrue(SpringContextUtils.isSingleton("testBean"));
    }

    @Test
    void getType_shouldReturnCorrectType() {
        Class<?> type = SpringContextUtils.getType("testBean");
        assertEquals(String.class, type);
    }

    // ─── A9: uninitialized state + first-context-wins ─────────────────
    //
    // The static applicationContext field is shared JVM-wide. These tests capture the
    // current value, exercise the uninitialized / guard behavior, and restore the
    // captured value afterwards so other test classes are neither depended upon nor
    // polluted (same disclosed reflection pattern used by FlexScheduledTaskLimitsTest).

    private static Object readStaticContext() throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        return field.get(null);
    }

    private static void writeStaticContext(Object value) throws Exception {
        Field field = SpringContextUtils.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    void uninitializedState_getBean_throwsIllegalState() throws Exception {
        Object original = readStaticContext();
        try {
            writeStaticContext(null);

            assertThrows(IllegalStateException.class, () -> SpringContextUtils.getBean("testBean"));
            assertThrows(IllegalStateException.class, () -> SpringContextUtils.getBean(String.class));
            assertThrows(IllegalStateException.class, () -> SpringContextUtils.getBean("testBean", String.class));
            assertThrows(IllegalStateException.class, () -> SpringContextUtils.containsBean("testBean"));
            assertThrows(IllegalStateException.class, () -> SpringContextUtils.isSingleton("testBean"));
            assertThrows(IllegalStateException.class, () -> SpringContextUtils.getType("testBean"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> SpringContextUtils.getBean("anything"));
            assertTrue(ex.getMessage().contains("ApplicationContext has not been initialized"),
                    "message should explain the missing initialization, was: " + ex.getMessage());
        } finally {
            writeStaticContext(original);
        }
    }

    @Test
    void setApplicationContext_calledTwice_firstContextWins() throws Exception {
        Object original = readStaticContext();
        org.springframework.context.support.StaticApplicationContext first =
                new org.springframework.context.support.StaticApplicationContext();
        org.springframework.context.support.StaticApplicationContext second =
                new org.springframework.context.support.StaticApplicationContext();
        try {
            first.getBeanFactory().registerSingleton("probeBean", "from-first-context");
            first.refresh();
            second.getBeanFactory().registerSingleton("probeBean", "from-second-context");
            second.getBeanFactory().registerSingleton("secondOnlyBean", "only-in-second");
            second.refresh();

            writeStaticContext(null);
            new SpringContextUtils().setApplicationContext(first);
            // The setter is guarded (first write wins): the second context is ignored.
            new SpringContextUtils().setApplicationContext(second);

            assertEquals("from-first-context", SpringContextUtils.getBean("probeBean"),
                    "getBean must resolve against the FIRST installed context");
            // The second context was never installed: a bean only present there is invisible.
            assertFalse(SpringContextUtils.containsBean("secondOnlyBean"));
        } finally {
            writeStaticContext(original);
            first.close();
            second.close();
        }
    }
}
