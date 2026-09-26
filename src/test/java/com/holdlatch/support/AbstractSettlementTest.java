package com.holdlatch.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.repository.OutboxRepository;
import com.holdlatch.repository.PersistentPaymentTransactionRepository;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import com.holdlatch.repository.RefundRepository;
import com.holdlatch.service.ExpiryMonitoringService;
import com.holdlatch.service.OutboxDispatcherService;
import com.holdlatch.support.ApiFixture.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Shared plumbing for tests that go all the way through checkout, the payment webhook and settlement. */
public abstract class AbstractSettlementTest extends AbstractHoldFlowTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected FakePaymentGateway gateway;
    @Autowired protected MutableClock clock;
    @Autowired protected PersistentSeatRepository seatRepository;
    @Autowired protected PersistentSectionRepository sectionRepository;
    @Autowired protected PersistentPaymentTransactionRepository paymentRepository;
    @Autowired protected RefundRepository refundRepository;
    @Autowired protected OutboxRepository outboxRepository;
    @Autowired protected OutboxDispatcherService dispatcher;
    @Autowired protected ExpiryMonitoringService expiry;

    protected ApiFixture api;
    protected User organizer;
    protected User alice;
    protected User bob;
    protected JsonNode event;
    protected Map<String, UUID> seats;
    protected UUID lawn;

    @BeforeEach
    void freshEventAndCleanFakes() throws Exception {
        clock.reset();
        gateway.reset();
        api = new ApiFixture(mvc, json);
        organizer = api.newUser("ORGANIZER");
        alice = api.newUser("CUSTOMER");
        bob = api.newUser("CUSTOMER");
        event = api.publishedHybridEvent(organizer, 6, 10);
        seats = api.seatIds(alice, event);
        lawn = api.sectionId(event, "Lawn");
    }

    protected List<UUID> ids(String... labels) {
        return java.util.Arrays.stream(labels).map(seats::get).toList();
    }

    protected JsonNode hold(User user, List<UUID> seatIds, Map<UUID, Integer> standing) throws Exception {
        return api.read(api.hold(user, event, seatIds, standing).andExpect(status().isCreated()));
    }

    protected ResultActions checkout(User user, String idempotencyKey, String holdToken) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = post("/api/checkout")
                .header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("holdToken", holdToken)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected JsonNode startCheckout(User user, String holdToken) throws Exception {
        return api.read(checkout(user, "key-" + UUID.randomUUID(), holdToken).andExpect(status().isCreated()));
    }

    protected String intentFor(UUID paymentId) {
        return gateway.created.stream().filter(c -> paymentId.toString().equals(c.metadata().get("paymentTransactionId")))
                .findFirst().orElseThrow().id();
    }

    protected ResultActions webhook(String kind, String intentId, UUID paymentId) throws Exception {
        return mvc.perform(post("/api/webhooks/stripe")
                .header("Stripe-Signature", FakePaymentGateway.VALID_SIGNATURE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("id", "evt_" + UUID.randomUUID(), "kind", kind, "intent", intentId,
                        "txId", paymentId == null ? "" : paymentId.toString(), "message", "Your card was declined."))));
    }

    protected void webhookOk(ResultActions actions) throws Exception {
        actions.andExpect(status().isOk());
    }

    protected ResultActions paymentSucceeded(UUID paymentId) throws Exception {
        return webhook("succeeded", intentFor(paymentId), paymentId);
    }

    protected JsonNode paymentStatus(User user, UUID paymentId) throws Exception {
        return api.read(api.get(user, "/api/checkout/" + paymentId).andExpect(status().isOk()));
    }

    /** Runs the outbox worker until the given aggregate's events are all delivered (or gives up after a few rounds). */
    protected void deliverOutbox(String aggregateId) {
        for (int i = 0; i < 5; i++) {
            dispatcher.dispatchOnce();
            List<OutboxEventRecord> mine = outboxRepository.findAll().stream().filter(e -> aggregateId.equals(e.getAggregateId())).toList();
            if (!mine.isEmpty() && mine.stream().allMatch(e -> e.getProcessedAt() != null)) {
                return;
            }
        }
        assertTrue(false, "outbox events for " + aggregateId + " were not delivered");
    }
}
