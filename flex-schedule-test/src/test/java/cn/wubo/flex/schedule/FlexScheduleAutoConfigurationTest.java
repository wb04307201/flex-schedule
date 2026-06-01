package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class FlexScheduleAutoConfigurationTest {

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    class WhenEnabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void threadPoolTaskScheduler_isCreated() {
            assertThat(context.containsBean("flexScheduleThreadPoolTaskScheduler")).isTrue();
            assertThat(context.getBean("flexScheduleThreadPoolTaskScheduler"))
                    .isInstanceOf(ThreadPoolTaskScheduler.class);
        }

        @Test
        void registrar_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskRegistrar")).isTrue();
            assertThat(context.getBean("flexScheduledTaskRegistrar"))
                    .isInstanceOf(FlexScheduledTaskRegistrar.class);
        }

        @Test
        void service_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskService")).isTrue();
            assertThat(context.getBean("flexScheduledTaskService"))
                    .isInstanceOf(FlexScheduledTaskService.class);
        }

        @Test
        void springContextUtils_isCreated() {
            assertThat(context.containsBean("flexScheduleSpringContextUtils")).isTrue();
            assertThat(context.getBean("flexScheduleSpringContextUtils"))
                    .isInstanceOf(SpringContextUtils.class);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = "flex.schedule.enabled=false")
    class WhenDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void noScheduler_isCreated() {
            assertThat(context.containsBean("flexScheduleThreadPoolTaskScheduler")).isFalse();
        }

        @Test
        void noRegistrar_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskRegistrar")).isFalse();
        }

        @Test
        void noService_isCreated() {
            assertThat(context.containsBean("flexScheduledTaskService")).isFalse();
        }

        @Test
        void noSpringContextUtils_isCreated() {
            assertThat(context.containsBean("flexScheduleSpringContextUtils")).isFalse();
        }
    }
}
