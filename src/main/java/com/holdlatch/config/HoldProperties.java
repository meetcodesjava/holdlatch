package com.holdlatch.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "holdlatch.hold")
public record HoldProperties(
        String tokenSecret,
        @DefaultValue("PT5M") Duration ttl,
        @DefaultValue("8") int maxTicketsPerHold,
        @DefaultValue("true") boolean rejectOrphanSeats,
        @DefaultValue("6") int standingRoomAttempts,
        @DefaultValue("PT1S") Duration catalogCacheTtl,
        @DefaultValue("PT2M") Duration paymentGrace) {

    public HoldProperties {
        if (tokenSecret == null || tokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("holdlatch.hold.token-secret (env HOLD_TOKEN_SECRET) must be at least 32 bytes");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("holdlatch.hold.ttl must be positive");
        }
        if (paymentGrace.isNegative()) {
            throw new IllegalArgumentException("holdlatch.hold.payment-grace must not be negative");
        }
        if (maxTicketsPerHold < 1 || standingRoomAttempts < 1) {
            throw new IllegalArgumentException("max-tickets-per-hold and standing-room-attempts must be at least 1");
        }
    }
}
