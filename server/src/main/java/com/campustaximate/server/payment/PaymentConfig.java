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
            @Value("${app.payment.timeout-ms:3000}") long timeoutMillis) {
        return secret.isBlank() ? new SimulatedPaymentGateway()
                : new TossPaymentGateway(builder, baseUrl, secret, Duration.ofMillis(timeoutMillis));
    }
}
