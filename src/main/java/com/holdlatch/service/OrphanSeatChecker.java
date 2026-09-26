package com.holdlatch.service;

import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.persistence.SeatRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds seats a selection would strand: a single free seat with no free
 * neighbour on either side (or a row end), which is very hard to sell later.
 * Only seats the selection itself newly strands are reported - a seat that was
 * already alone before is not the buyer's fault.
 */
final class OrphanSeatChecker {

    private OrphanSeatChecker() {}

    /** @param rowSeats every seat in one row (any order); BOOKED seats count as taken */
    static List<UUID> newOrphans(List<SeatRecord> rowSeats, Set<UUID> selected) {
        List<SeatRecord> seats = new ArrayList<>(rowSeats);
        seats.sort(Comparator.comparingInt(SeatRecord::getSeatNumber));

        Set<UUID> loneBefore = loneFreeSeats(seats, Set.of());
        Set<UUID> loneAfter = loneFreeSeats(seats, selected);
        loneAfter.removeAll(loneBefore);
        return new ArrayList<>(loneAfter);
    }

    private static Set<UUID> loneFreeSeats(List<SeatRecord> sorted, Set<UUID> extraTaken) {
        Set<UUID> lone = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!isFree(sorted.get(i), extraTaken)) {
                continue;
            }
            boolean freeLeft = i > 0 && adjacent(sorted.get(i - 1), sorted.get(i)) && isFree(sorted.get(i - 1), extraTaken);
            boolean freeRight = i < sorted.size() - 1 && adjacent(sorted.get(i), sorted.get(i + 1)) && isFree(sorted.get(i + 1), extraTaken);
            if (!freeLeft && !freeRight) {
                lone.add(sorted.get(i).getId());
            }
        }
        return lone;
    }

    private static boolean isFree(SeatRecord seat, Set<UUID> extraTaken) {
        return seat.getStatus() == SeatAllocationStatus.AVAILABLE && !extraTaken.contains(seat.getId());
    }

    // A gap in the numbering (an aisle) splits a row into separate blocks.
    private static boolean adjacent(SeatRecord left, SeatRecord right) {
        return right.getSeatNumber() == left.getSeatNumber() + 1;
    }
}
