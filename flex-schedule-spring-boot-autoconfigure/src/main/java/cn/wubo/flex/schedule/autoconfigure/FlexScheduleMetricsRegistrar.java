package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.MetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

/**
 * Wires the {@link MetricsRecorder} into the {@link FlexScheduledTaskRegistrar}
 * after both beans are created, avoiding circular dependency issues.
 */
@Slf4j
public class FlexScheduleMetricsRegistrar implements InitializingBean {

    private final FlexScheduledTaskRegistrar registrar;
    private final MetricsRecorder metricsRecorder;

    public FlexScheduleMetricsRegistrar(FlexScheduledTaskRegistrar registrar, MetricsRecorder metricsRecorder) {
        this.registrar = registrar;
        this.metricsRecorder = metricsRecorder;
    }

    @Override
    public void afterPropertiesSet() {
        if (metricsRecorder != null) {
            registrar.setMetricsRecorder(metricsRecorder);
            log.info("Wired MetricsRecorder into FlexScheduledTaskRegistrar");
        }
    }
}
