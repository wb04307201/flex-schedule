package cn.wubo.flex.schedule.autoconfigure.endpoint;

/**
 * Access control interface for the flex schedule actuator endpoint.
 * <p>
 * Implementations can provide custom authorization logic for write operations
 * (POST/DELETE) on the endpoint. The default implementation checks:
 * <ol>
 *   <li>{@code flex.schedule.endpoint.write-enabled} — must be {@code true} for POST/DELETE</li>
 *   <li>{@code flex.schedule.endpoint.allowed-beans} — bean must be in the allowlist (if non-empty)</li>
 * </ol>
 * <p>
 * Users can provide their own implementation by registering a bean of this type,
 * which will override the default.
 */
public interface EndpointAccessControl {

    /**
     * Checks whether adding a task via the endpoint is allowed.
     *
     * @param beanName   the target bean name from the request
     * @param methodName the target method name from the request
     * @return {@code true} if the operation is permitted
     */
    boolean canAddTask(String beanName, String methodName);

    /**
     * Checks whether cancelling a task via the endpoint is allowed.
     *
     * @param taskName the task name to cancel
     * @return {@code true} if the operation is permitted
     */
    boolean canCancelTask(String taskName);
}
