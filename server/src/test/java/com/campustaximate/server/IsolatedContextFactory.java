package com.campustaximate.server;

import java.util.*;
import org.springframework.test.context.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 캐시된 서로 다른 컨텍스트의 시계와 스케줄러가 같은 H2를 건드리지 않게 한다. */
public class IsolatedContextFactory implements ContextCustomizerFactory {
    @Override public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
        return new Isolation();
    }
    private static final class Isolation implements ContextCustomizer {
        @Override public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration merged) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("수동 테스트 회차", Map.of("app.scheduling.enabled", "false")));
            String url = context.getEnvironment().getProperty("spring.datasource.url", "");
            if (url.startsWith("jdbc:h2:mem:")) context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("컨텍스트 전용 DB", Map.of("spring.datasource.url",
                            "jdbc:h2:mem:test_" + UUID.randomUUID().toString().replace("-", "") + url.substring(url.indexOf(';') < 0 ? url.length() : url.indexOf(';')))));
        }
        @Override public boolean equals(Object other) { return other instanceof Isolation; }
        @Override public int hashCode() { return Isolation.class.hashCode(); }
    }
}
