package cn.wubo.flex.schedule.core;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Utility class that holds a static reference to the Spring {@link ApplicationContext}.
 * <p>
 * Registered as a bean via {@code ApplicationContextAware}, it allows non-Spring-managed
 * code (e.g., {@link BeanMethodRunnable}) to look up beans at runtime.
 * </p>
 */
public class SpringContextUtils implements ApplicationContextAware {

    private static volatile ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (SpringContextUtils.applicationContext == null) {
            SpringContextUtils.applicationContext = applicationContext;
        }
    }

    private static ApplicationContext getApplicationContext() {
        ApplicationContext ctx = applicationContext;
        if (ctx == null) {
            throw new IllegalStateException(
                    "ApplicationContext has not been initialized. "
                            + "Ensure SpringContextUtils is registered as a Spring bean.");
        }
        return ctx;
    }

    public static Object getBean(String name) {
        return getApplicationContext().getBean(name);
    }

    public static <T> T getBean(Class<T> clazz) {
        return getApplicationContext().getBean(clazz);
    }

    public static <T> T getBean(String name, Class<T> clazz) {
        return getApplicationContext().getBean(name, clazz);
    }

    public static boolean containsBean(String name) {
        return getApplicationContext().containsBean(name);
    }

    public static boolean isSingleton(String name) {
        return getApplicationContext().isSingleton(name);
    }

    public static Class<?> getType(String name) {
        return getApplicationContext().getType(name);
    }
}
