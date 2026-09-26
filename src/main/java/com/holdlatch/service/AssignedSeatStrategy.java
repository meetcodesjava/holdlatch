package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.dto.EventDtos.SeatView;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.exception.SeatConflictException;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SectionRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Validates a request for specific numbered seats and prices it, using only the
 * short-lived catalog cache - so an attempt that is doomed to lose costs no
 * database queries.
 */
@Component
class AssignedSeatStrategy {

    record SelectedSeat(UUID seatId, UUID sectionId, String row, int number) {}

    record Selection(List<SelectedSeat> seats, Map<UUID, SectionRecord> sectionsById) {
        long totalCents() {
            return seats.stream().mapToLong(s -> sectionsById.get(s.sectionId()).getPriceCents()).sum();
        }

        List<UUID> seatIds() {
            return seats.stream().map(SelectedSeat::seatId).toList();
        }
    }

    private final CatalogCache catalog;
    private final HoldProperties props;

    AssignedSeatStrategy(CatalogCache catalog, HoldProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    Selection plan(EventRecord event, List<SectionRecord> eventSections, List<UUID> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new InvalidSelectionException("DUPLICATE_SEAT", "The same seat was requested more than once.");
        }

        Map<UUID, SectionRecord> seated = new HashMap<>();
        for (SectionRecord section : eventSections) {
            if (section.getKind() == SectionKind.ASSIGNED && event.getSeatingMode().allows(section.getKind())) {
                seated.put(section.getId(), section);
            }
        }
        Map<UUID, UUID> sectionOfSeat = catalog.seatSections(event.getId());

        List<SelectedSeat> selected = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        List<UUID> taken = new ArrayList<>();
        for (UUID seatId : seatIds) {
            UUID sectionId = sectionOfSeat.get(seatId);
            SeatView view = sectionId == null || !seated.containsKey(sectionId) ? null : catalog.seats(sectionId).byId().get(seatId);
            if (view == null) {
                missing.add(seatId);
            } else if (view.status() != SeatAllocationStatus.AVAILABLE) {
                taken.add(seatId);
            } else {
                selected.add(new SelectedSeat(seatId, sectionId, view.row(), view.number()));
            }
        }
        if (!missing.isEmpty()) {
            throw new InvalidSelectionException("SEAT_NOT_FOUND", "Some seats do not belong to this event.").with("seatIds", missing);
        }
        if (!taken.isEmpty()) {
            throw new SeatConflictException("SEAT_UNAVAILABLE", "Some of the seats are already booked.").with("seatIds", taken);
        }

        if (props.rejectOrphanSeats()) {
            rejectIfItStrandsASeat(selected);
        }
        Map<UUID, SectionRecord> used = new HashMap<>();
        selected.forEach(s -> used.put(s.sectionId(), seated.get(s.sectionId())));
        return new Selection(selected, used);
    }

    private void rejectIfItStrandsASeat(List<SelectedSeat> selected) {
        Set<UUID> selectedIds = new HashSet<>();
        Map<UUID, Set<String>> rowsPerSection = new LinkedHashMap<>();
        for (SelectedSeat seat : selected) {
            selectedIds.add(seat.seatId());
            rowsPerSection.computeIfAbsent(seat.sectionId(), k -> new HashSet<>()).add(seat.row());
        }

        List<UUID> stranded = new ArrayList<>();
        for (Map.Entry<UUID, Set<String>> entry : rowsPerSection.entrySet()) {
            CatalogCache.SectionSeats layout = catalog.seats(entry.getKey());
            for (String row : entry.getValue()) {
                List<OrphanSeatChecker.Cell> cells = layout.byRow().get(row).stream()
                        .map(v -> new OrphanSeatChecker.Cell(v.id(), v.number(), v.status() != SeatAllocationStatus.AVAILABLE))
                        .toList();
                stranded.addAll(OrphanSeatChecker.newOrphans(cells, selectedIds));
            }
        }
        if (!stranded.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WOULD_ORPHAN_SEAT",
                    "That selection would leave a single empty seat that is hard to sell. Please pick seats that do not strand one.")
                    .with("seatIds", stranded);
        }
    }
}
