package com.holdlatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.service.OrphanSeatChecker.Cell;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrphanSeatCheckerTest {

    /** A row with the given seat numbers, all free. */
    private static List<Cell> row(int... numbers) {
        List<Cell> cells = new ArrayList<>();
        for (int n : numbers) {
            cells.add(new Cell(UUID.randomUUID(), n, false));
        }
        return cells;
    }

    private static List<Cell> row(int count) {
        int[] numbers = new int[count];
        for (int i = 0; i < count; i++) {
            numbers[i] = i + 1;
        }
        return row(numbers);
    }

    private static void sell(List<Cell> cells, int index) {
        Cell c = cells.get(index);
        cells.set(index, new Cell(c.id(), c.number(), true));
    }

    private static Set<UUID> pick(List<Cell> cells, int... positions) {
        Set<UUID> ids = new HashSet<>();
        for (int position : positions) {
            ids.add(cells.get(position - 1).id());
        }
        return ids;
    }

    @Test
    void takingSeatsFromTheEdgeInwardsStrandsNothing() {
        List<Cell> seats = row(6);
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 1, 2)).isEmpty());
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 5, 6)).isEmpty());
    }

    @Test
    void leavingASingleSeatAtTheRowEndIsAnOrphan() {
        List<Cell> seats = row(6);
        assertEquals(List.of(seats.get(0).id()), OrphanSeatChecker.newOrphans(seats, pick(seats, 2, 3)));
    }

    @Test
    void leavingASingleSeatBetweenTwoTakenBlocksIsAnOrphan() {
        List<Cell> seats = row(6);
        sell(seats, 0);
        sell(seats, 1);
        assertEquals(List.of(seats.get(2).id()), OrphanSeatChecker.newOrphans(seats, pick(seats, 4, 5, 6)));
    }

    @Test
    void takingTheWholeRowStrandsNothing() {
        List<Cell> seats = row(4);
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 1, 2, 3, 4)).isEmpty());
    }

    @Test
    void aSeatThatWasAlreadyAloneIsNotTheBuyersFault() {
        List<Cell> seats = row(5);
        sell(seats, 1);
        // Seat 1 is already stranded by the earlier sale of seat 2; taking 3-5 leaves it exactly as it was.
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 3, 4, 5)).isEmpty());
        // Taking only 4-5 strands seat 3, which is new - but seat 1 is still not blamed on this buyer.
        assertEquals(List.of(seats.get(2).id()), OrphanSeatChecker.newOrphans(seats, pick(seats, 4, 5)));
    }

    @Test
    void anAisleGapInTheNumberingSplitsTheRowIntoSeparateBlocks() {
        // seats 1-2, aisle, seats 4-5: taking 4 leaves 5 alone even though 2 is free
        List<Cell> seats = row(1, 2, 4, 5);
        assertEquals(List.of(seats.get(3).id()), OrphanSeatChecker.newOrphans(seats, pick(seats, 3)));
    }

    @Test
    void rowOrderDoesNotMatter() {
        List<Cell> seats = row(6);
        List<Cell> shuffled = new ArrayList<>(seats);
        java.util.Collections.reverse(shuffled);
        assertEquals(List.of(seats.get(0).id()), OrphanSeatChecker.newOrphans(shuffled, pick(seats, 2, 3)));
    }
}
