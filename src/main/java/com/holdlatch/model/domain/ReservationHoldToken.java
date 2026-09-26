package com.holdlatch.model.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the settlement step needs to know about a hold, carried inside a
 * signed token so no server-side lookup is needed to read it. The token proves
 * what was held and its price; AeroKV's TTL remains the authority on whether
 * the hold is still alive.
 */
public record ReservationHoldToken(
        String holdId,
        UUID userId,
        UUID eventId,
        Instant expiresAt,
        long amountCents,
        String currency,
        List<String> keys,
        List<UUID> seatIds,
        List<StandingLine> standing) {

    public record StandingLine(UUID sectionId, int quantity, long unitPriceCents) {}

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
