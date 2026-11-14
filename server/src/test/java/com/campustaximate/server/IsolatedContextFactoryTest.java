package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class IsolatedContextFactoryTest {
    @Test void differentCachedContextsUseDifferentDatabasesAndNoAutomaticSchedulers() {
        var customizer=new IsolatedContextFactory().createContextCustomizer(getClass(),List.of());
        try(var first=new GenericApplicationContext();var second=new GenericApplicationContext()) {
            first.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url","jdbc:h2:mem:taximate;MODE=MySQL"));
            second.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url","jdbc:h2:mem:taximate;MODE=MySQL"));
            customizer.customizeContext(first,null);customizer.customizeContext(second,null);
            assertThat(first.getEnvironment().getProperty("spring.datasource.url")).isNotEqualTo(second.getEnvironment().getProperty("spring.datasource.url"));
            assertThat(first.getEnvironment().getProperty("spring.datasource.url")).endsWith(";MODE=MySQL");
            assertThat(first.getEnvironment().getProperty("app.scheduling.enabled")).isEqualTo("false");
            assertThat(second.getEnvironment().getProperty("app.scheduling.enabled")).isEqualTo("false");
        }
    }
}
