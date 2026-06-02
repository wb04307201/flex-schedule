package cn.wubo.flex.schedule.autoconfigure.endpoint;

import java.util.Collections;
import java.util.Set;

/**
 * Default {@link EndpointAccessControl} implementation based on configuration properties.
 * <p>
 * Write operations (POST/DELETE) are <strong>disabled by default</strong> and must be
 * explicitly enabled via {@code flex.schedule.endpoint.write-enabled=true}.
 * <p>
 * When an allowlist is configured, only beans in the list can be scheduled via the endpoint.
 */
public class DefaultEndpointAccessControl implements EndpointAccessControl {

    private final boolean writeEnabled;
    private final Set<String> allowedBeans;

    public DefaultEndpointAccessControl(boolean writeEnabled, Set<String> allowedBeans) {
        this.writeEnabled = writeEnabled;
        this.allowedBeans = allowedBeans != null ? allowedBeans : Collections.emptySet();
    }

    @Override
    public boolean canAddTask(String beanName, String methodName) {
        if (!writeEnabled) {
            return false;
        }
        if (allowedBeans.isEmpty()) {
            return true;
        }
        return allowedBeans.contains(beanName);
    }

    @Override
    public boolean canCancelTask(String taskName) {
        return writeEnabled;
    }
}
