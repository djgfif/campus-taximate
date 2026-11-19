package com.campustaximate.server.payment;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentConfig {
    @Bean
    PaymentGateway paymentGateway(RestClient.Builder builder,
            @Value("${app.toss.secret-key:}") String secret,
            @Value("${app.toss.base-url:https://api.tosspayments.com}") String baseUrl,
            @Value("${app.payment.timeout-ms:3000}") long timeoutMillis,
            org.springframework.core.env.Environment environment,
            @Value("${app.payment.simulated:false}") boolean simulated) {
        if (simulated) {
            if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo")))
                throw new IllegalStateException("Simulated payments require the demo profile");
            if (!secret.isBlank()) throw new IllegalStateException("Simulated payments must not use a real payment key");
            return new SimulatedPaymentGateway();
        }
        if (secret.isBlank()) throw new IllegalStateException("TOSS_SECRET_KEY is required for real payments");
        return new TossPaymentGateway(builder, baseUrl, secret, Duration.ofMillis(timeoutMillis));
    }
}
