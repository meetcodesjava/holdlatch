package com.holdlatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class StandingRoomSlotPickerTest {

    private final RandomGenerator random = new java.util.Random(42);

    @Test
    void picksDistinctValuesInsideTheBoundForEverySizeRelationship() {
        int[][] cases = {{1, 1}, {1, 100}, {3, 10}, {5, 10}, {9, 10}, {10, 10}, {40, 1000}, {600, 1000}};
        for (int[] c : cases) {
            int count = c[0], bound = c[1];
            int[] picked = GeneralAdmissionStrategy.pickDistinct(count, bound, random);
            assertEquals(count, picked.length);
            Set<Integer> unique = Arrays.stream(picked).boxed().collect(Collectors.toSet());
            assertEquals(count, unique.size(), "values must be distinct for " + count + "/" + bound);
            assertTrue(Arrays.stream(picked).allMatch(v -> v >= 0 && v < bound));
        }
    }

    @Test
    void choosingEverySlotReturnsTheWholeRange() {
        Set<Integer> all = Arrays.stream(GeneralAdmissionStrategy.pickDistinct(8, 8, random)).boxed().collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7)), all);
    }

    @Test
    void spreadsPicksAcrossTheWholeWindowInsteadOfAlwaysTheSameSlots() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(GeneralAdmissionStrategy.pickDistinct(1, 20, random)[0]);
        }
        assertEquals(20, seen.size());
    }

    @Test
    void cannotPickMoreThanExists() {
        assertThrows(IllegalArgumentException.class, () -> GeneralAdmissionStrategy.pickDistinct(3, 2, random));
        assertThrows(IllegalArgumentException.class, () -> GeneralAdmissionStrategy.pickDistinct(-1, 2, random));
    }
}
