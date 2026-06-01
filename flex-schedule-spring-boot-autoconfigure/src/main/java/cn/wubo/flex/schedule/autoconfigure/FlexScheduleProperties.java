package cn.wubo.flex.schedule.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
