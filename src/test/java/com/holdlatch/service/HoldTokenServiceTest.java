package com.holdlatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.holdlatch.config.HoldProperties;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.model.domain.ReservationHoldToken;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTokenServiceTest {

    private static HoldTokenService service(String secret) {
        HoldProperties props = new HoldProperties(secret, Duration.ofMinutes(5), 8, true, 6, Duration.ofSeconds(1), Duration.ofMinutes(2));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new HoldTokenService(props, mapper);
    }

    private static ReservationHoldToken sample(Instant expiresAt) {
        return new ReservationHoldToken("hold-1", UUID.randomUUID(), UUID.randomUUID(), expiresAt, 12_000, "USD",
                List.of("s:a:b"), List.of(UUID.randomUUID()),
                List.of(new ReservationHoldToken.StandingLine(UUID.randomUUID(), 2, 2000)));
    }

    private static final String SECRET = "unit-test-secret-that-is-long-enough-1234";

    @Test
    void roundTripPreservesEverythingIncludingPriceAndKeys() {
        HoldTokenService tokens = service(SECRET);
        ReservationHoldToken original = sample(Instant.parse("2030-01-01T00:00:00Z"));
        ReservationHoldToken parsed = tokens.parse(tokens.issue(original));
        assertEquals(original, parsed);
    }

    @Test
    void anyEditToThePayloadOrSignatureIsRejected() {
        HoldTokenService tokens = service(SECRET);
        String token = tokens.issue(sample(Instant.parse("2030-01-01T00:00:00Z")));
        String[] parts = token.split("\\.");

        String flippedPayload = (parts[0].charAt(5) == 'A' ? "B" : "A");
        String editedPayload = parts[0].substring(0, 5) + flippedPayload + parts[0].substring(6);
        assertThrows(InvalidSelectionException.class, () -> tokens.parse(editedPayload + "." + parts[1]));

        String editedSignature = parts[1].substring(0, parts[1].length() - 1) + (parts[1].endsWith("A") ? "B" : "A");
        assertThrows(InvalidSelectionException.class, () -> tokens.parse(parts[0] + "." + editedSignature));
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String forged = service("a-completely-different-secret-value-9876").issue(sample(Instant.parse("2030-01-01T00:00:00Z")));
        assertThrows(InvalidSelectionException.class, () -> service(SECRET).parse(forged));
    }

    @Test
    void garbageOversizedAndMalformedInputIsRejectedCleanly() {
        HoldTokenService tokens = service(SECRET);
        for (String bad : new String[] {null, "", "nodot", "a.b.c", ".", "abc.", ".abc", "x".repeat(20_000)}) {
            assertThrows(InvalidSelectionException.class, () -> tokens.parse(bad), "should reject: " + bad);
        }
    }

    @Test
    void expiryIsReportedButLeftToTheCallerToEnforce() {
        HoldTokenService tokens = service(SECRET);
        Instant expires = Instant.parse("2030-01-01T00:00:00Z");
        ReservationHoldToken parsed = tokens.parse(tokens.issue(sample(expires)));
        assertFalse(parsed.isExpired(expires.minusSeconds(1)));
        assertTrue(parsed.isExpired(expires));
        assertTrue(parsed.isExpired(expires.plusSeconds(1)));
    }

    @Test
    void refusesToRunWithAWeakSecret() {
        assertThrows(IllegalArgumentException.class, () -> service("short"));
        assertThrows(IllegalArgumentException.class, () -> service(null));
    }
}
