package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.exception.SeatConflictException;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates a request for specific numbered seats and prices it. Touches only the database. */
@Component
class AssignedSeatStrategy {

    record Selection(List<SeatRecord> seats, Map<UUID, SectionRecord> sectionsById) {
        long totalCents() {
            return seats.stream().mapToLong(s -> sectionsById.get(s.getSectionId()).getPriceCents()).sum();
        }
    }

    private final PersistentSeatRepository seatRepository;
    private final PersistentSectionRepository sectionRepository;
    private final HoldProperties props;

    AssignedSeatStrategy(PersistentSeatRepository seatRepository, PersistentSectionRepository sectionRepository, HoldProperties props) {
        this.seatRepository = seatRepository;
        this.sectionRepository = sectionRepository;
        this.props = props;
    }

    Selection plan(EventRecord event, List<UUID> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new InvalidSelectionException("DUPLICATE_SEAT", "The same seat was requested more than once.");
        }

        List<SeatRecord> seats = seatRepository.findByEventIdAndIdIn(event.getId(), seatIds);
        if (seats.size() != seatIds.size()) {
            Set<UUID> found = seats.stream().map(SeatRecord::getId).collect(Collectors.toSet());
            List<UUID> missing = seatIds.stream().filter(id -> !found.contains(id)).toList();
            throw new InvalidSelectionException("SEAT_NOT_FOUND", "Some seats do not belong to this event.").with("seatIds", missing);
        }

        List<UUID> taken = seats.stream().filter(s -> s.getStatus() != SeatAllocationStatus.AVAILABLE).map(SeatRecord::getId).toList();
        if (!taken.isEmpty()) {
            throw new SeatConflictException("SEAT_UNAVAILABLE", "Some of the seats are already booked.").with("seatIds", taken);
        }

        Set<UUID> sectionIds = seats.stream().map(SeatRecord::getSectionId).collect(Collectors.toSet());
        Map<UUID, SectionRecord> sections = sectionRepository.findByEventIdAndIdIn(event.getId(), sectionIds).stream()
                .collect(Collectors.toMap(SectionRecord::getId, s -> s));
        for (SectionRecord section : sections.values()) {
            if (section.getKind() != SectionKind.ASSIGNED || !event.getSeatingMode().allows(section.getKind())) {
                throw new InvalidSelectionException("NOT_A_SEATED_SECTION", "Section " + section.getName() + " has no numbered seats.");
            }
        }

        if (props.rejectOrphanSeats()) {
            rejectIfItStrandsASeat(seats);
        }
        return new Selection(seats, sections);
    }

    private void rejectIfItStrandsASeat(List<SeatRecord> selectedSeats) {
        Set<UUID> selectedIds = selectedSeats.stream().map(SeatRecord::getId).collect(Collectors.toSet());
        Map<UUID, Set<String>> rowsPerSection = new LinkedHashMap<>();
        for (SeatRecord seat : selectedSeats) {
            rowsPerSection.computeIfAbsent(seat.getSectionId(), k -> new HashSet<>()).add(seat.getRowLabel());
        }

        List<UUID> stranded = new ArrayList<>();
        for (Map.Entry<UUID, Set<String>> entry : rowsPerSection.entrySet()) {
            Map<String, List<SeatRecord>> byRow = seatRepository.findBySectionIdAndRowLabelIn(entry.getKey(), entry.getValue()).stream()
                    .collect(Collectors.groupingBy(SeatRecord::getRowLabel));
            for (List<SeatRecord> row : byRow.values()) {
                stranded.addAll(OrphanSeatChecker.newOrphans(row, selectedIds));
            }
        }
        if (!stranded.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WOULD_ORPHAN_SEAT",
                    "That selection would leave a single empty seat that is hard to sell. Please pick seats that do not strand one.")
                    .with("seatIds", stranded);
        }
    }
}
