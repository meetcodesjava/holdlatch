package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.dto.ReservationDtos.HoldRequest;
import com.holdlatch.dto.ReservationDtos.StandingRequest;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.exception.NotFoundException;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SectionRecord;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Turns a hold request into a validated, priced plan. It reads only from the
 * short-lived catalog cache, so a request that is going to lose - a taken seat,
 * a bad selection - is refused without a single database query.
 */
@Service
public class SeatAllocationService {

    record Plan(EventRecord event, AssignedSeatStrategy.Selection seats, List<GeneralAdmissionStrategy.Selection> standing,
                long totalCents, String currency) {}

    private final CatalogCache catalog;
    private final AssignedSeatStrategy assigned;
    private final GeneralAdmissionStrategy standing;
    private final HoldProperties props;
    private final Clock clock;

    SeatAllocationService(CatalogCache catalog, AssignedSeatStrategy assigned, GeneralAdmissionStrategy standing,
                          HoldProperties props, Clock clock) {
        this.catalog = catalog;
        this.assigned = assigned;
        this.standing = standing;
        this.props = props;
        this.clock = clock;
    }

    Plan plan(UUID eventId, HoldRequest request) {
        List<UUID> seatIds = request.seatIdsOrEmpty();
        List<StandingRequest> standingRequests = request.standingOrEmpty();

        int tickets = seatIds.size() + standingRequests.stream().mapToInt(StandingRequest::quantity).sum();
        if (tickets == 0) {
            throw new InvalidSelectionException("EMPTY_SELECTION", "Select at least one seat or standing ticket.");
        }
        if (tickets > props.maxTicketsPerHold()) {
            throw new InvalidSelectionException("TOO_MANY_TICKETS", "At most " + props.maxTicketsPerHold() + " tickets can be held at once.");
        }

        EventRecord event = catalog.event(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found."));
        List<SectionRecord> sections = catalog.sections(eventId);
        if (!event.isOnSale()) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_NOT_ON_SALE", "This event is not on sale.");
        }
        if (!event.getStartsAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_ALREADY_STARTED", "This event has already started.");
        }

        AssignedSeatStrategy.Selection seatSelection = seatIds.isEmpty() ? null : assigned.plan(event, sections, seatIds);
        List<GeneralAdmissionStrategy.Selection> standingSelections =
                standingRequests.isEmpty() ? List.of() : standing.plan(event, sections, standingRequests);

        Set<String> currencies = new HashSet<>();
        if (seatSelection != null) {
            seatSelection.sectionsById().values().forEach(s -> currencies.add(s.getCurrency()));
        }
        for (GeneralAdmissionStrategy.Selection selection : standingSelections) {
            SectionRecord section = selection.section();
            currencies.add(section.getCurrency());
        }
        if (currencies.size() != 1) {
            throw new InvalidSelectionException("MIXED_CURRENCY", "All selected sections must be priced in the same currency.");
        }

        long total = (seatSelection == null ? 0 : seatSelection.totalCents())
                + standingSelections.stream().mapToLong(GeneralAdmissionStrategy.Selection::totalCents).sum();
        return new Plan(event, seatSelection, standingSelections, total, currencies.iterator().next());
    }
}
