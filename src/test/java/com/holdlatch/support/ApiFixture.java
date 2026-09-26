package com.holdlatch.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Drives the real HTTP API in tests: creates users and events and performs authenticated calls. */
public final class ApiFixture {

    public record User(UUID id, String token) {}

    // Each helper call comes from its own fake client address so tests never trip the login rate limiter.
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    private final MockMvc mvc;
    private final ObjectMapper json;

    public ApiFixture(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public User newUser(String role) throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String ip = freshIp();
        JsonNode created = read(mvc.perform(MockMvcRequestBuilders.post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse", "displayName", "Tester", "role", role))))
                .andExpect(status().isCreated()));
        JsonNode login = read(mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse")))).andExpect(status().isOk()));
        return new User(UUID.fromString(created.get("id").asText()), login.get("accessToken").asText());
    }

    /** A hybrid event: a 2-row seated "Floor" (rowSize seats per row, row A and B) and a standing "Lawn". Returned unpublished. */
    public JsonNode createHybridEvent(User organizer, int rowSize, int lawnCapacity) throws Exception {
        Map<String, Object> body = Map.of(
                "name", "Flash Sale Gig",
                "venue", "Test Arena",
                "startsAt", Instant.now().plus(30, ChronoUnit.DAYS).toString(),
                "seatingMode", "HYBRID",
                "sections", List.of(
                        Map.of("name", "Floor", "kind", "ASSIGNED", "currency", "USD", "priceCents", 5000,
                                "rows", List.of(Map.of("label", "A", "seats", rowSize), Map.of("label", "B", "seats", rowSize))),
                        Map.of("name", "Lawn", "kind", "GENERAL_ADMISSION", "currency", "USD", "priceCents", 2000, "capacity", lawnCapacity)));
        return read(post(organizer, "/api/events", body).andExpect(status().isCreated()));
    }

    public JsonNode publishedHybridEvent(User organizer, int rowSize, int lawnCapacity) throws Exception {
        JsonNode event = createHybridEvent(organizer, rowSize, lawnCapacity);
        post(organizer, "/api/events/" + event.get("id").asText() + "/publish", null).andExpect(status().isOk());
        return event;
    }

    public UUID sectionId(JsonNode event, String name) {
        for (JsonNode section : event.get("sections")) {
            if (section.get("name").asText().equals(name)) {
                return UUID.fromString(section.get("id").asText());
            }
        }
        throw new IllegalArgumentException("no section " + name);
    }

    /** Seat ids of the Floor section keyed by label such as "A1" or "B4". */
    public Map<String, UUID> seatIds(User viewer, JsonNode event) throws Exception {
        UUID eventId = UUID.fromString(event.get("id").asText());
        JsonNode seats = read(get(viewer, "/api/events/" + eventId + "/sections/" + sectionId(event, "Floor") + "/seats")
                .andExpect(status().isOk()));
        Map<String, UUID> byLabel = new java.util.LinkedHashMap<>();
        for (JsonNode seat : seats) {
            byLabel.put(seat.get("row").asText() + seat.get("number").asInt(), UUID.fromString(seat.get("id").asText()));
        }
        return byLabel;
    }

    public ResultActions hold(User user, JsonNode event, List<UUID> seatIds, Map<UUID, Integer> standing) throws Exception {
        List<Map<String, Object>> standingBody = new ArrayList<>();
        standing.forEach((section, qty) -> standingBody.add(Map.of("sectionId", section, "quantity", qty)));
        return post(user, "/api/events/" + event.get("id").asText() + "/holds", Map.of("seatIds", seatIds, "standing", standingBody));
    }

    public ResultActions post(User user, String url, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(url).header("Authorization", "Bearer " + user.token());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    public ResultActions get(User user, String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + user.token()));
    }

    public JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String freshIp() {
        int n = NEXT_IP.getAndIncrement();
        return "172.16." + (n / 250) + "." + (n % 250 + 1);
    }
}
