package cn.wubo.flex.schedule.autoconfigure;

import cn.wubo.flex.schedule.core.TaskLimits;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties for flex scheduling.
 */
@Data
@ConfigurationProperties(prefix = "flex.schedule")
public class FlexScheduleProperties {
    private Boolean enabled = Boolean.TRUE;
    private Integer poolSize = 16;
    private String threadNamePrefix = "FlexScheduleThreadPool-";
    private Boolean removeOnCancel = Boolean.TRUE;
    private Long awaitTerminationSeconds = 30L;

    private Endpoint endpoint = new Endpoint();
    private Limits limits = new Limits();

    /**
     * Endpoint security configuration.
     */
    @Data
    public static class Endpoint {
        /**
         * Whether write operations (POST/DELETE) are enabled on the actuator endpoint.
         * Default is false for security reasons.
         */
        private Boolean writeEnabled = Boolean.FALSE;

        /**
         * Set of bean names that are allowed to be scheduled via the actuator endpoint.
         * If empty, all beans are allowed (when write-enabled is true).
         */
        private Set<String> allowedBeans = new HashSet<>();
    }

    /**
     * Global task scheduling limits.
     * <p>
     * Both {@code minInterval} and {@code maxLifetime} default to {@code null}, meaning no limit.
     * When either is set, the {@link TaskLimits.Mode} determines enforcement:
     * STRICT throws on violation, WARN logs and allows, OFF disables all checks.
     */
    @Data
    public static class Limits {
        /** Minimum trigger interval for FIXED_DELAY / FIXED_RATE / ONE_SHOT. null = no limit. */
        private Duration minInterval;
        /** Maximum task lifetime before auto-cancel. null = no limit. */
        private Duration maxLifetime;
        /** Enforcement mode. Default: STRICT. */
        private TaskLimits.Mode mode = TaskLimits.Mode.STRICT;
    }
}