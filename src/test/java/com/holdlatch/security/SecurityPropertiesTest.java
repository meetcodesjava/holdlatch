package com.holdlatch.security;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.holdlatch.config.SecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static SecurityProperties with(String secret, Duration ttl, int strength) {
        return new SecurityProperties(secret, "holdlatch", ttl, strength, new SecurityProperties.RateLimit(10, 5, 5, 1));
    }

    @Test
    void refusesToStartWithAMissingOrShortSigningSecret() {
        assertThrows(IllegalArgumentException.class, () -> with(null, Duration.ofHours(1), 12));
        assertThrows(IllegalArgumentException.class, () -> with("", Duration.ofHours(1), 12));
        assertThrows(IllegalArgumentException.class, () -> with("too-short", Duration.ofHours(1), 12));
    }

    @Test
    void refusesUnsafeTokenLifetimeAndHashingStrength() {
        String secret = "x".repeat(40);
        assertThrows(IllegalArgumentException.class, () -> with(secret, Duration.ZERO, 12));
        assertThrows(IllegalArgumentException.class, () -> with(secret, Duration.ofHours(1), 3));
        assertThrows(IllegalArgumentException.class, () -> with(secret, Duration.ofHours(1), 17));
        with(secret, Duration.ofHours(1), 12);
    }
}
