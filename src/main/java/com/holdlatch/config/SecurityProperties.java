package com.holdlatch.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "holdlatch.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("holdlatch") String issuer,
        @DefaultValue("PT1H") Duration accessTokenTtl,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue RateLimit rateLimit) {

    private static final int MIN_SECRET_BYTES = 32;

    public SecurityProperties {
        // Fail at startup rather than run with a guessable signing key.
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "holdlatch.security.jwt-secret (env JWT_SECRET) must be set to at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("holdlatch.security.access-token-ttl must be positive");
        }
        if (bcryptStrength < 4 || bcryptStrength > 16) {
            throw new IllegalArgumentException("holdlatch.security.bcrypt-strength must be between 4 and 16");
        }
    }

    public record RateLimit(
            @DefaultValue("100") int apiCapacity,
            @DefaultValue("50") double apiRefillPerSecond,
            @DefaultValue("10") int authCapacity,
            @DefaultValue("0.2") double authRefillPerSecond) {}
}
