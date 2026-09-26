package com.holdlatch.dto;

import com.holdlatch.model.domain.EventStatus;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.domain.SeatingMode;
import com.holdlatch.model.domain.SectionKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EventDtos {

    private EventDtos() {}

    public record RowSpec(
            @NotBlank @Size(max = 10) String label,
            @Min(1) @Max(500) int seats) {}

    /** ASSIGNED sections list their rows; GENERAL_ADMISSION sections give a capacity. */
    public record SectionSpec(
            @NotBlank @Size(max = 100) String name,
            @NotNull SectionKind kind,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") String currency,
            @PositiveOrZero long priceCents,
            @Valid @Size(max = 200) List<RowSpec> rows,
            @Min(1) @Max(100_000) Integer capacity) {}

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @NotNull SeatingMode seatingMode,
            @NotEmpty @Size(max = 50) @Valid List<SectionSpec> sections) {}

    public record SectionView(UUID id, String name, SectionKind kind, String currency, long priceCents, int capacity, long available) {}

    public record EventSummary(UUID id, String name, String venue, Instant startsAt, SeatingMode seatingMode, EventStatus status) {}

    public record EventDetail(UUID id, String name, String venue, Instant startsAt, SeatingMode seatingMode, EventStatus status,
                              List<SectionView> sections) {}

    public record SeatView(UUID id, String row, int number, SeatAllocationStatus status) {}

    public record PagedResponse<T>(List<T> items, int page, int size, long total) {}
}
