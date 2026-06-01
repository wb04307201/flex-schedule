package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.BeanMethodRunnable;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.exception.BeanMethodRunnableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(BeanMethodRunnableTest.TestConfig.class)
class BeanMethodRunnableTest {

    @Configuration
    static class TestConfig {
        @Bean
        public SpringContextUtils springContextUtils() {
            return new SpringContextUtils();
        }

        @Bean
        public SampleBean sampleBean() {
            return new SampleBean();
        }
    }

    public static class SampleBean {
        private int callCount = 0;
        private String lastMessage = null;

        public void noArgMethod() {
            callCount++;
        }

        public void withStringArg(String message) {
            lastMessage = message;
            callCount++;
        }

        public void withIntArg(int value) {
            callCount++;
        }

        public int getCallCount() {
            return callCount;
        }

        public String getLastMessage() {
            return lastMessage;
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void run_noArgMethod_shouldInvoke() {
        BeanMethodRunnable runnable = new BeanMethodRunnable("sampleBean", "noArgMethod");
        runnable.run();

        SampleBean bean = context.getBean(SampleBean.class);
        assertTrue(bean.getCallCount() >= 1);
    }

    @Test
    void run_withStringArg_shouldInvoke() {
        BeanMethodRunnable runnable = new BeanMethodRunnable("sampleBean", "withStringArg", List.of("hello"));
        runnable.run();

        SampleBean bean = context.getBean(SampleBean.class);
        assertEquals("hello", bean.getLastMessage());
    }

    @Test
    void run_withPrimitiveArg_shouldInvoke() {
        BeanMethodRunnable runnable = new BeanMethodRunnable("sampleBean", "withIntArg", List.of(42));
        runnable.run();

        SampleBean bean = context.getBean(SampleBean.class);
        assertTrue(bean.getCallCount() >= 1);
    }

    @Test
    void run_nonExistentBean_shouldThrow() {
        BeanMethodRunnable runnable = new BeanMethodRunnable("nonExistentBean", "method");
        assertThrows(Exception.class, runnable::run);
    }

    @Test
    void run_nonExistentMethod_shouldThrow() {
        BeanMethodRunnable runnable = new BeanMethodRunnable("sampleBean", "nonExistentMethod");
        assertThrows(BeanMethodRunnableException.class, runnable::run);
    }

    @Test
    void constructor_nullBeanName_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new BeanMethodRunnable(null, "method"));
    }

    @Test
    void constructor_emptyMethodName_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new BeanMethodRunnable("bean", ""));
    }
}
