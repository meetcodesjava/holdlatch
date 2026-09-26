package com.holdlatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReservationDtos {

    private ReservationDtos() {}

    public record StandingRequest(@NotNull UUID sectionId, @Min(1) @Max(100) int quantity) {}

    /** Any mix of numbered seats and standing-room tickets, held together or not at all. */
    public record HoldRequest(
            @Size(max = 50) List<@NotNull UUID> seatIds,
            @Size(max = 20) @Valid List<@NotNull StandingRequest> standing) {

        public List<UUID> seatIdsOrEmpty() {
            return seatIds == null ? List.of() : seatIds;
        }

        public List<StandingRequest> standingOrEmpty() {
            return standing == null ? List.of() : standing;
        }
    }

    public record HoldResponse(
            String holdToken,
            String holdId,
            Instant expiresAt,
            long totalCents,
            String currency,
            List<UUID> seatIds,
            List<StandingRequest> standing) {}

    public record ReleaseRequest(@NotBlank @Size(max = 16_384) String holdToken) {}
}
