package com.holdlatch.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.support.AbstractHoldFlowTest;
import com.holdlatch.support.ApiFixture;
import com.holdlatch.support.ApiFixture.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Holds live for one second here, so expiry can be observed for real instead of simulated. */
@SpringBootTest(properties = "holdlatch.hold.ttl=PT1S")
@AutoConfigureMockMvc
class HoldExpiryIntegrationTest extends AbstractHoldFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void anAbandonedHoldFreesItselfAndALateReleaseCannotTouchTheNewHolder() throws Exception {
        ApiFixture api = new ApiFixture(mvc, json);
        User organizer = api.newUser("ORGANIZER");
        User alice = api.newUser("CUSTOMER");
        User bob = api.newUser("CUSTOMER");
        User carol = api.newUser("CUSTOMER");
        JsonNode event = api.publishedHybridEvent(organizer, 6, 10);
        Map<String, UUID> seats = api.seatIds(alice, event);
        List<UUID> pair = List.of(seats.get("A1"), seats.get("A2"));

        JsonNode aliceHold = api.read(api.hold(alice, event, pair, Map.of()).andExpect(status().isCreated()));
        api.hold(bob, event, pair, Map.of()).andExpect(status().isConflict());

        Thread.sleep(1500);

        api.hold(bob, event, pair, Map.of()).andExpect(status().isCreated());

        // Alice comes back late and releases her expired hold. Bob's new hold must survive it.
        api.post(alice, "/api/holds/release", Map.of("holdToken", aliceHold.get("holdToken").asText())).andExpect(status().isNoContent());
        api.hold(carol, event, pair, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SEAT_UNAVAILABLE")));
    }
}
