package cn.wubo.flex.schedule;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
class TestApplication {

    private static final TestApplication INSTANCE = new TestApplication();

    /** Singleton accessor used by tests that need to register this class as a Spring bean. */
    static TestApplication getInstance() {
        return INSTANCE;
    }

    /** No-op method usable as a BeanMethodRunnable target. */
    public void noOp() {
        // intentionally empty
    }
}