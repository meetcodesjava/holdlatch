package com.holdlatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "holdlatch.stripe")
public record StripeProperties(
        String secretKey,
        String webhookSecret,
        @DefaultValue("300") long webhookToleranceSeconds,
        @DefaultValue("10000") int timeoutMs) {

    /** Payments are switched on only when both the API key and the webhook signing secret are present. */
    public boolean configured() {
        return secretKey != null && !secretKey.isBlank() && webhookSecret != null && !webhookSecret.isBlank();
    }
}
