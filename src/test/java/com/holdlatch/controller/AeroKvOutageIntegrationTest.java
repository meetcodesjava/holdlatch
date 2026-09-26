package com.holdlatch.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.engine.aerokv.AeroKvClient;
import com.holdlatch.engine.aerokv.AeroKvUnavailableException;
import com.holdlatch.support.AbstractPostgresTest;
import com.holdlatch.support.ApiFixture;
import com.holdlatch.support.ApiFixture.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** When AeroKV is unreachable the API must say so plainly (503), never hang, never leak internals, never pretend a hold exists. */
@SpringBootTest
@AutoConfigureMockMvc
class AeroKvOutageIntegrationTest extends AbstractPostgresTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AeroKvClient aeroKv;

    @Test
    void holdFailsWith503WhenAeroKvIsDown() throws Exception {
        ApiFixture api = new ApiFixture(mvc, json);
        User organizer = api.newUser("ORGANIZER");
        User alice = api.newUser("CUSTOMER");
        JsonNode event = api.publishedHybridEvent(organizer, 6, 10);
        Map<String, UUID> seats = api.seatIds(alice, event);

        when(aeroKv.multiHold(anyList(), anyString(), any())).thenThrow(new AeroKvUnavailableException("connection refused"));
        when(aeroKv.releaseIfOwner(anyString(), anyString())).thenThrow(new AeroKvUnavailableException("connection refused"));

        api.hold(alice, event, List.of(seats.get("A1"), seats.get("A2")), Map.of()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("RESERVATION_ENGINE_UNAVAILABLE")));
    }
}
