package com.holdlatch.service;

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

    /** One seat of a row: its number and whether it is already sold. */
    record Cell(UUID id, int number, boolean taken) {}

    private OrphanSeatChecker() {}

    /** @param row every seat in one row (any order); sold seats count as taken */
    static List<UUID> newOrphans(List<Cell> row, Set<UUID> selected) {
        List<Cell> cells = new ArrayList<>(row);
        cells.sort(Comparator.comparingInt(Cell::number));

        Set<UUID> loneBefore = loneFreeSeats(cells, Set.of());
        Set<UUID> loneAfter = loneFreeSeats(cells, selected);
        loneAfter.removeAll(loneBefore);
        return new ArrayList<>(loneAfter);
    }

    private static Set<UUID> loneFreeSeats(List<Cell> sorted, Set<UUID> extraTaken) {
        Set<UUID> lone = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!isFree(sorted.get(i), extraTaken)) {
                continue;
            }
            boolean freeLeft = i > 0 && adjacent(sorted.get(i - 1), sorted.get(i)) && isFree(sorted.get(i - 1), extraTaken);
            boolean freeRight = i < sorted.size() - 1 && adjacent(sorted.get(i), sorted.get(i + 1)) && isFree(sorted.get(i + 1), extraTaken);
            if (!freeLeft && !freeRight) {
                lone.add(sorted.get(i).id());
            }
        }
        return lone;
    }

    private static boolean isFree(Cell seat, Set<UUID> extraTaken) {
        return !seat.taken() && !extraTaken.contains(seat.id());
    }

    // A gap in the numbering (an aisle) splits a row into separate blocks.
    private static boolean adjacent(Cell left, Cell right) {
        return right.number() == left.number() + 1;
    }
}
