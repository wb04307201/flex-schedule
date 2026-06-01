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
}
