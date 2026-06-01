package cn.wubo.flex.schedule.core;

import cn.wubo.flex.schedule.exception.BeanMethodRunnableException;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.Assert;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Runnable} that reflectively invokes a method on a Spring bean.
 * Supports CGLIB and JDK dynamic proxies by resolving the target class
 * via {@link AopUtils#getTargetClass(Object)}.
 */
public class BeanMethodRunnable implements Runnable {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();

    static {
        PRIMITIVE_TO_WRAPPER.put(boolean.class, Boolean.class);
        PRIMITIVE_TO_WRAPPER.put(byte.class, Byte.class);
        PRIMITIVE_TO_WRAPPER.put(char.class, Character.class);
        PRIMITIVE_TO_WRAPPER.put(short.class, Short.class);
        PRIMITIVE_TO_WRAPPER.put(int.class, Integer.class);
        PRIMITIVE_TO_WRAPPER.put(long.class, Long.class);
        PRIMITIVE_TO_WRAPPER.put(float.class, Float.class);
        PRIMITIVE_TO_WRAPPER.put(double.class, Double.class);
    }

    private final String beanName;
    private final String methodName;
    private final List<Object> methodParams;
    private volatile Method cachedMethod;
    private volatile Class<?> cachedClass;

    public BeanMethodRunnable(String beanName, String methodName) {
        this(beanName, methodName, new ArrayList<>());
    }

    public BeanMethodRunnable(String beanName, String methodName, List<Object> methodParams) {
        Assert.hasText(beanName, "beanName must not be empty");
        Assert.hasText(methodName, "methodName must not be empty");
        Assert.notNull(methodParams, "methodParams must not be null");
        this.beanName = beanName;
        this.methodName = methodName;
        this.methodParams = methodParams;
    }

    @Override
    public void run() {
        Object target = SpringContextUtils.getBean(beanName);
        try {
            // Resolve the actual target class, unwrapping any AOP proxy
            Class<?> targetClass = AopUtils.getTargetClass(target);
            Method method = getOrResolveMethod(targetClass);
            method.setAccessible(true);
            if (methodParams.isEmpty()) {
                method.invoke(target);
            } else {
                method.invoke(target, methodParams.toArray());
            }
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new BeanMethodRunnableException(e.getMessage(), e);
        }
    }

    private Method getOrResolveMethod(Class<?> clazz) throws NoSuchMethodException {
        // Return cached method if the class hasn't changed
        if (cachedMethod != null && cachedClass == clazz) {
            return cachedMethod;
        }

        // Resolve and cache
        Method method = resolveMethod(clazz, methodName, methodParams);
        cachedMethod = method;
        cachedClass = clazz;
        return method;
    }

    private Method resolveMethod(Class<?> clazz, String name, List<Object> params) throws NoSuchMethodException {
        if (params.isEmpty()) {
            return clazz.getMethod(name);
        }

        // First try exact class matching
        Class<?>[] exactTypes = params.stream().map(Object::getClass).toArray(Class<?>[]::new);
        try {
            return clazz.getMethod(name, exactTypes);
        } catch (NoSuchMethodException ignored) {
            // Fall through to assignable matching
        }

        // Try assignable matching (handles primitives, interfaces, superclasses)
        for (Method candidate : clazz.getMethods()) {
            if (!candidate.getName().equals(name)) continue;
            Class<?>[] paramTypes = candidate.getParameterTypes();
            if (paramTypes.length != params.size()) continue;

            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> formalType = paramTypes[i];
                Class<?> actualType = params.get(i).getClass();
                if (!isAssignable(formalType, actualType)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return candidate;
            }
        }

        throw new NoSuchMethodException(clazz.getName() + "." + name + " with compatible parameter types");
    }

    private boolean isAssignable(Class<?> targetType, Class<?> sourceType) {
        if (targetType.isAssignableFrom(sourceType)) {
            return true;
        }
        // Handle primitive types
        Class<?> wrappedTarget = PRIMITIVE_TO_WRAPPER.getOrDefault(targetType, targetType);
        Class<?> wrappedSource = PRIMITIVE_TO_WRAPPER.getOrDefault(sourceType, sourceType);
        return wrappedTarget.isAssignableFrom(wrappedSource);
    }
}
