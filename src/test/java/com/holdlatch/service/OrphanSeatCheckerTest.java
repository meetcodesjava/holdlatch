package com.holdlatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.model.persistence.SeatRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrphanSeatCheckerTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();

    private static List<SeatRecord> row(int... numbers) {
        List<SeatRecord> seats = new ArrayList<>();
        for (int n : numbers) {
            seats.add(new SeatRecord(EVENT, SECTION, "A", n));
        }
        return seats;
    }

    private static List<SeatRecord> row(int count) {
        int[] numbers = new int[count];
        for (int i = 0; i < count; i++) {
            numbers[i] = i + 1;
        }
        return row(numbers);
    }

    private static Set<UUID> pick(List<SeatRecord> seats, int... numbers) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (int n : numbers) {
            ids.add(seats.get(n - 1).getId());
        }
        return ids;
    }

    @Test
    void takingSeatsFromTheEdgeInwardsStrandsNothing() {
        List<SeatRecord> seats = row(6);
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 1, 2)).isEmpty());
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 5, 6)).isEmpty());
    }

    @Test
    void leavingASingleSeatAtTheRowEndIsAnOrphan() {
        List<SeatRecord> seats = row(6);
        List<UUID> orphans = OrphanSeatChecker.newOrphans(seats, pick(seats, 2, 3));
        assertEquals(List.of(seats.get(0).getId()), orphans);
    }

    @Test
    void leavingASingleSeatBetweenTwoTakenBlocksIsAnOrphan() {
        List<SeatRecord> seats = row(6);
        seats.get(0).markBooked();
        seats.get(1).markBooked();
        List<UUID> orphans = OrphanSeatChecker.newOrphans(seats, pick(seats, 4, 5, 6));
        assertEquals(List.of(seats.get(2).getId()), orphans);
    }

    @Test
    void takingTheWholeRowOrEveryFreeSeatStrandsNothing() {
        List<SeatRecord> seats = row(4);
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 1, 2, 3, 4)).isEmpty());
    }

    @Test
    void aSeatThatWasAlreadyAloneIsNotTheBuyersFault() {
        List<SeatRecord> seats = row(5);
        seats.get(1).markBooked();
        // Seat 1 is already stranded by the earlier booking of seat 2; taking 3-5 leaves it exactly as it was.
        assertTrue(OrphanSeatChecker.newOrphans(seats, pick(seats, 3, 4, 5)).isEmpty());
        // Taking only 4-5 strands seat 3, which is new - but seat 1 is still not blamed on this buyer.
        List<UUID> orphans = OrphanSeatChecker.newOrphans(seats, pick(seats, 4, 5));
        assertEquals(List.of(seats.get(2).getId()), orphans);
    }

    @Test
    void anAisleGapInTheNumberingSplitsTheRowIntoSeparateBlocks() {
        // seats 1-2, aisle, seats 4-5: taking 4 leaves 5 alone even though 2 is free
        List<SeatRecord> seats = row(1, 2, 4, 5);
        List<UUID> orphans = OrphanSeatChecker.newOrphans(seats, pick(seats, 3));
        assertEquals(List.of(seats.get(3).getId()), orphans);
    }
}
