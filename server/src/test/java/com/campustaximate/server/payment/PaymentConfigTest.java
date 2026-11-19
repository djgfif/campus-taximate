package com.campustaximate.server.payment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class PaymentConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PaymentConfig.class).withBean(RestClient.Builder.class, RestClient::builder);
    @Test void productionWithoutKeyFailsEvenWhenDemoFlagIsTrue() {
        context.withPropertyValues("app.demo.enabled=true").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.demo.enabled=true", "app.payment.simulated=true")
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void demoStillRequiresExplicitSimulation() {
        context.withInitializer(c -> c.getEnvironment().setActiveProfiles("demo"))
                .run(c -> assertThat(c).hasFailed());
        context.withInitializer(c -> c.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("app.payment.simulated=true")
                .run(c -> assertThat(c.getBean(PaymentGateway.class)).isInstanceOf(SimulatedPaymentGateway.class));
    }
    @Test void productionWithKeyUsesRealGatewayWithoutCallingIt() {
        context.withPropertyValues("app.toss.secret-key=test-secret")
                .run(c -> assertThat(c.getBean(PaymentGateway.class)).isInstanceOf(TossPaymentGateway.class));
    }
    @Test void realKeyCannotBeSilentlyIgnoredBySimulation() {
        context.withInitializer(c -> c.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("app.payment.simulated=true", "app.toss.secret-key=test-secret")
                .run(c -> assertThat(c).hasFailed());
    }
}
