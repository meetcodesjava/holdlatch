package com.holdlatch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.payment.DisabledPaymentGateway;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.payment.StripePaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    PaymentGateway paymentGateway(StripeProperties props, ObjectMapper objectMapper) {
        if (props.configured()) {
            return new StripePaymentGateway(props, objectMapper);
        }
        log.warn("Stripe keys are not set: payment endpoints will answer 503 until STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET are provided");
        return new DisabledPaymentGateway();
    }
}
