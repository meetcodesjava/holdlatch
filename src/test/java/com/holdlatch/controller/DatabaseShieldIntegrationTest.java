package com.holdlatch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.support.AbstractHoldFlowTest;
import com.holdlatch.support.ApiFixture;
import com.holdlatch.support.ApiFixture.User;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole point of putting AeroKV in front of the database, measured: with the
 * caches warm, a hold that loses the race for a seat must cost ZERO database
 * statements, and one that wins must cost exactly one (the post-acquire re-check).
 */
@SpringBootTest(properties = {"holdlatch.hold.catalog-cache-ttl=PT30S", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
class DatabaseShieldIntegrationTest extends AbstractHoldFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void holdsThatLoseTheRaceNeverTouchTheDatabase() throws Exception {
        ApiFixture api = new ApiFixture(mvc, json);
        User organizer = api.newUser("ORGANIZER");
        User alice = api.newUser("CUSTOMER");
        User bob = api.newUser("CUSTOMER");
        User carol = api.newUser("CUSTOMER");
        JsonNode event = api.publishedHybridEvent(organizer, 6, 10);
        Map<String, UUID> seats = api.seatIds(alice, event);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        // Alice wins A1+A2. This also warms every cache the hold path uses.
        api.hold(alice, event, List.of(seats.get("A1"), seats.get("A2")), Map.of()).andExpect(status().isCreated());

        long before = stats.getPrepareStatementCount();
        int attempts = 60;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> api.hold(bob, event, List.of(seats.get("A1"), seats.get("A2"), seats.get("A3")), Map.of())
                    .andReturn().getResponse().getStatus()));
        }
        for (Future<Integer> result : results) {
            assertEquals(409, result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        long spentOnLosers = stats.getPrepareStatementCount() - before;
        assertEquals(0, spentOnLosers, attempts + " rejected holds must not run a single database statement");

        // A winner pays exactly one query: re-reading its seats to catch a booking that slipped in after the cache was filled.
        long beforeWinner = stats.getPrepareStatementCount();
        api.hold(carol, event, List.of(seats.get("B1"), seats.get("B2")), Map.of()).andExpect(status().isCreated());
        assertEquals(1, stats.getPrepareStatementCount() - beforeWinner);
    }
}
