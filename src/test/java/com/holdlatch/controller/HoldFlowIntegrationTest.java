package com.holdlatch.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import com.holdlatch.support.AbstractHoldFlowTest;
import com.holdlatch.support.ApiFixture;
import com.holdlatch.support.ApiFixture.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class HoldFlowIntegrationTest extends AbstractHoldFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PersistentSeatRepository seatRepository;
    @Autowired PersistentSectionRepository sectionRepository;
    @Autowired PlatformTransactionManager txManager;

    ApiFixture api;
    User organizer;
    User alice;
    User bob;
    JsonNode event;
    Map<String, UUID> seats;
    UUID lawn;

    @BeforeEach
    void freshPublishedEvent() throws Exception {
        api = new ApiFixture(mvc, json);
        organizer = api.newUser("ORGANIZER");
        alice = api.newUser("CUSTOMER");
        bob = api.newUser("CUSTOMER");
        event = api.publishedHybridEvent(organizer, 6, 20);
        seats = api.seatIds(alice, event);
        lawn = api.sectionId(event, "Lawn");
    }

    private List<UUID> ids(String... labels) {
        List<UUID> result = new ArrayList<>();
        for (String label : labels) {
            result.add(seats.get(label));
        }
        return result;
    }

    @Test
    void onlyOrganizersCanCreateEventsAndDraftsStayHiddenUntilPublished() throws Exception {
        api.post(alice, "/api/events", Map.of("name", "x")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));

        JsonNode draft = api.createHybridEvent(organizer, 4, 10);
        String draftUrl = "/api/events/" + draft.get("id").asText();
        api.get(alice, draftUrl).andExpect(status().isNotFound());
        api.get(organizer, draftUrl).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DRAFT")));
        api.hold(alice, draft, List.of(UUID.randomUUID()), Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("EVENT_NOT_ON_SALE")));

        api.post(api.newUser("ORGANIZER"), draftUrl + "/publish", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("NOT_EVENT_OWNER")));
        api.post(organizer, draftUrl + "/publish", null).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ON_SALE")));
        api.get(alice, draftUrl).andExpect(status().isOk());
        api.get(alice, "/api/events?page=0&size=50").andExpect(status().isOk());
    }

    @Test
    void eventDetailShowsSeatsAndStandingRoomAvailability() throws Exception {
        api.get(alice, "/api/events/" + event.get("id").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(2)))
                .andExpect(jsonPath("$.sections[?(@.name=='Floor')].available", hasItem(12)))
                .andExpect(jsonPath("$.sections[?(@.name=='Lawn')].available", hasItem(20)));
        assertEquals(12, seats.size());
    }

    @Test
    void holdReturnsPricedSignedTokenAndTheSecondBuyerGetsAConflict() throws Exception {
        api.hold(alice, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents", is(10_000)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.seatIds", hasSize(2)))
                .andExpect(jsonPath("$.holdToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        api.hold(bob, event, ids("A1", "A2", "A3"), Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SEAT_UNAVAILABLE")));
    }

    @Test
    void aFailedMultiSeatHoldTakesNothing() throws Exception {
        api.hold(alice, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());
        api.hold(bob, event, ids("A1", "A2", "A3"), Map.of()).andExpect(status().isConflict());
        // A3 must still be free for someone else, proving the failed attempt did not keep it.
        User carol = api.newUser("CUSTOMER");
        api.hold(carol, event, ids("A3", "A4"), Map.of()).andExpect(status().isCreated());
    }

    @Test
    void selectionsThatStrandASingleSeatAreRejected() throws Exception {
        api.hold(alice, event, ids("A2"), Map.of()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("WOULD_ORPHAN_SEAT")))
                .andExpect(jsonPath("$.seatIds", hasItem(seats.get("A1").toString())));
        api.hold(alice, event, ids("B2", "B3", "B4", "B5", "B6"), Map.of()).andExpect(status().isUnprocessableEntity());
        api.hold(alice, event, ids("B1", "B2", "B3", "B4", "B5", "B6", "A1", "A2", "A3"), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("TOO_MANY_TICKETS")));
        api.hold(alice, event, ids("B1", "B2", "B3"), Map.of()).andExpect(status().isCreated());
    }

    @Test
    void standingRoomIsPricedPerTicketAndStopsAtCapacity() throws Exception {
        api.hold(alice, event, List.of(), Map.of(lawn, 3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents", is(6_000)))
                .andExpect(jsonPath("$.seatIds", hasSize(0)))
                .andExpect(jsonPath("$.standing[0].quantity", is(3)));
        assertEquals(3, AEROKV.countLiveKeysWithPrefix("g:" + lawn));

        // 18 of 20 tickets get sold for real: only 2 remain, so asking for 3 is sold out and 2 works.
        new TransactionTemplate(txManager).executeWithoutResult(s -> assertEquals(1, sectionRepository.reserveStandingRoom(lawn, 18)));
        api.hold(bob, event, List.of(), Map.of(lawn, 3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SECTION_SOLD_OUT")));
        api.hold(bob, event, List.of(), Map.of(lawn, 2)).andExpect(status().isCreated());
    }

    @Test
    void seatsAndStandingRoomAreHeldTogetherOrNotAtAll() throws Exception {
        api.hold(alice, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());
        long before = AEROKV.countLiveKeysWithPrefix("g:" + lawn);

        api.hold(bob, event, ids("A1", "A2"), Map.of(lawn, 2)).andExpect(status().isConflict());
        assertEquals(before, AEROKV.countLiveKeysWithPrefix("g:" + lawn), "a failed mixed hold must not leak standing slots");

        api.hold(bob, event, ids("B1", "B2"), Map.of(lawn, 2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents", is(14_000)));
        assertEquals(before + 2, AEROKV.countLiveKeysWithPrefix("g:" + lawn));
    }

    @Test
    void releaseFreesTheSeatsButOnlyForTheirOwner() throws Exception {
        JsonNode hold = api.read(api.hold(alice, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated()));
        String token = hold.get("holdToken").asText();

        api.post(bob, "/api/holds/release", Map.of("holdToken", token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("NOT_HOLD_OWNER")));
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isConflict());

        api.post(alice, "/api/holds/release", Map.of("holdToken", token)).andExpect(status().isNoContent());
        api.post(alice, "/api/holds/release", Map.of("holdToken", token)).andExpect(status().isNoContent());
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());
    }

    @Test
    void aTamperedTokenCannotBeUsedToReleaseAnything() throws Exception {
        JsonNode hold = api.read(api.hold(alice, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated()));
        String token = hold.get("holdToken").asText();
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("abc") ? "xyz" : "abc");

        api.post(alice, "/api/holds/release", Map.of("holdToken", tampered)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_HOLD_TOKEN")));
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isConflict());
    }

    @Test
    void anAlreadyBookedSeatIsRefusedWithoutAskingAeroKv() throws Exception {
        UUID booked = seats.get("B6");
        new TransactionTemplate(txManager).executeWithoutResult(s -> assertEquals(1, seatRepository.markBookedIfAvailable(List.of(booked))));
        int callsBefore = AEROKV.received().size();

        api.hold(alice, event, List.of(booked, seats.get("B5")), Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SEAT_UNAVAILABLE")))
                .andExpect(jsonPath("$.seatIds", hasItem(booked.toString())));
        assertTrue(AEROKV.received().stream().skip(callsBefore).noneMatch(c -> c.contains(booked.toString())),
                "a seat the database already knows is booked must never reach AeroKV");
    }

    @Test
    void fortyBuyersRacingForTheSameSeatsProduceExactlyOneWinner() throws Exception {
        int buyers = 40;
        List<User> users = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            users.add(api.newUser("CUSTOMER"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (User user : users) {
            Callable<Integer> attempt = () -> {
                start.await();
                return api.hold(user, event, ids("B3", "B4"), Map.of()).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(attempt));
        }
        start.countDown();

        int winners = 0;
        int conflicts = 0;
        for (Future<Integer> result : results) {
            int code = result.get(60, TimeUnit.SECONDS);
            if (code == 201) {
                winners++;
            } else if (code == 409) {
                conflicts++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, winners, "exactly one buyer may hold the seats");
        assertEquals(buyers - 1, conflicts, "everyone else must get a clean conflict");
    }

    @Test
    void invalidRequestsGetClearCodes() throws Exception {
        api.hold(alice, event, List.of(), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("EMPTY_SELECTION")));
        api.hold(alice, event, List.of(UUID.randomUUID()), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("SEAT_NOT_FOUND")));
        api.hold(alice, event, List.of(seats.get("A1"), seats.get("A1")), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SEAT")));
        api.hold(alice, event, ids("A1"), Map.of(seats.get("A1"), 1)).andExpect(status().isBadRequest());
        api.hold(alice, event, List.of(), Map.of(UUID.randomUUID(), 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("SECTION_NOT_FOUND")));
        api.hold(alice, event, List.of(), Map.of(sectionOf("Floor"), 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("NOT_A_STANDING_SECTION")));
        api.post(alice, "/api/events/" + UUID.randomUUID() + "/holds", Map.of("seatIds", ids("A1"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("EVENT_NOT_FOUND")));
    }

    @Test
    void holdingRequiresLogin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/events/" + event.get("id").asText() + "/holds")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        api.get(alice, "/api/events/" + event.get("id").asText()).andExpect(jsonPath("$.name", not(is(""))));
    }

    private UUID sectionOf(String name) {
        return api.sectionId(event, name);
    }
}
