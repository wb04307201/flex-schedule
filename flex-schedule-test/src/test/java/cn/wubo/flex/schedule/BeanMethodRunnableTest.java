package cn.wubo.flex.schedule;

import cn.wubo.flex.schedule.core.BeanMethodRunnable;
import cn.wubo.flex.schedule.core.SpringContextUtils;
import cn.wubo.flex.schedule.exception.BeanMethodRunnableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(BeanMethodRunnableTest.TestConfig.class)
class BeanMethodRunnableTest {

    /** Counts invocations passing through the JDK proxy's advice chain (A18 proof of routing). */
    static final AtomicInteger PROXY_ADVICE_HITS = new AtomicInteger();

    /** Interface a JDK dynamic proxy can be built from. */
    public interface CountingService {
        void bump();

        int count();
    }

    /** Concrete target behind the JDK interface proxy. */
    public static class CountingServiceImpl implements CountingService {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public void bump() {
            counter.incrementAndGet();
        }

        @Override
        public int count() {
            return counter.get();
        }
    }

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

        @Bean
        public CountingService jdkProxyBean() {
            CountingServiceImpl target = new CountingServiceImpl();
            ProxyFactory factory = new ProxyFactory(target);
            factory.setInterfaces(CountingService.class);
            factory.setProxyTargetClass(false); // force a JDK interface proxy
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                PROXY_ADVICE_HITS.incrementAndGet();
                return invocation.proceed();
            });
            return (CountingService) factory.getProxy();
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

    // ─── A18: method caching + JDK dynamic proxy support ─────────────

    private static Object readField(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void run_repeatedInvocations_cacheResolvedMethod() throws Exception {
        BeanMethodRunnable runnable = new BeanMethodRunnable("sampleBean", "noArgMethod");
        SampleBean bean = context.getBean(SampleBean.class);
        int countBefore = bean.getCallCount();

        // First run resolves reflectively; second run must hit the cache.
        runnable.run();
        runnable.run();

        assertEquals(countBefore + 2, bean.getCallCount(),
                "both runs must invoke the target method");

        // Secondary check: the resolved Method/Class are populated after execution.
        assertNotNull(readField(runnable, "cachedMethod"),
                "cachedMethod must be populated after the first run");
        assertSame(SampleBean.class, readField(runnable, "cachedClass"),
                "cachedClass must be the resolved target class");
    }

    @Test
    void run_jdkProxyBean_resolvesTargetClassButExecutesThroughProxy() throws Exception {
        CountingService proxyBean = context.getBean("jdkProxyBean", CountingService.class);
        assertTrue(java.lang.reflect.Proxy.isProxyClass(proxyBean.getClass()),
                "test premise: bean must be a JDK dynamic proxy");

        BeanMethodRunnable runnable = new BeanMethodRunnable("jdkProxyBean", "bump");
        int hitsBefore = PROXY_ADVICE_HITS.get();
        runnable.run();
        runnable.run();
        int hitsAfterFirstPair = PROXY_ADVICE_HITS.get();

        // Unwrap the real target to read its counter without adding advice hits.
        org.springframework.aop.framework.Advised advised =
                (org.springframework.aop.framework.Advised) proxyBean;
        CountingServiceImpl target =
                (CountingServiceImpl) advised.getTargetSource().getTarget();
        assertEquals(2, target.count(),
                "proxied bean method must be invoked twice through the proxy chain");
        assertEquals(2, hitsAfterFirstPair - hitsBefore,
                "invocations must route through the AOP advice chain (proxy not bypassed)");

        // The cache proves the TARGET class (not the proxy class) was resolved via
        // AopUtils.getTargetClass — the documented unwrapping behavior.
        assertSame(CountingServiceImpl.class, readField(runnable, "cachedClass"),
                "BeanMethodRunnable must cache the AOP target class, not the JDK proxy class");
        assertNotNull(readField(runnable, "cachedMethod"));
    }
}
