package com.holdlatch.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.domain.RefundStatus;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.support.AbstractSettlementTest;
import com.holdlatch.support.ApiFixture.User;
import com.holdlatch.support.FakePaymentConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakePaymentConfig.class)
class SettlementFlowIntegrationTest extends AbstractSettlementTest {

    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager txManager;

    @Test
    void aSuccessfulPaymentBooksTheSeatsAndStandingTicketsAndConfirmsTheOrder() throws Exception {
        JsonNode hold = hold(alice, ids("A1", "A2"), Map.of(lawn, 2));
        JsonNode checkout = startCheckout(alice, hold.get("holdToken").asText());
        UUID paymentId = UUID.fromString(checkout.get("paymentId").asText());

        assertEquals(14_000, checkout.get("amountCents").asLong());
        assertEquals(1, gateway.created.size());
        assertEquals(14_000, gateway.created.get(0).amountCents());
        assertEquals("USD", gateway.created.get(0).currency());
        assertEquals("PENDING", paymentStatus(alice, paymentId).get("state").asText());

        webhookOk(paymentSucceeded(paymentId));

        JsonNode status = paymentStatus(alice, paymentId);
        assertEquals("CONFIRMED", status.get("state").asText());
        String orderId = status.get("orderId").asText();

        api.get(alice, "/api/orders/" + orderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.totalCents", is(14_000)))
                .andExpect(jsonPath("$.items", hasSize(3)));

        assertEquals(SeatAllocationStatus.BOOKED, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus());
        assertEquals(SeatAllocationStatus.BOOKED, seatRepository.findById(seats.get("A2")).orElseThrow().getStatus());
        assertEquals(SeatAllocationStatus.AVAILABLE, seatRepository.findById(seats.get("A3")).orElseThrow().getStatus());
        assertEquals(2, sectionRepository.findById(lawn).orElseThrow().getSoldQuantity());

        // Nothing is left held in AeroKV: the database is now the only record of who owns what.
        assertEquals(0, AEROKV.countLiveKeysWithPrefix("s:" + event.get("id").asText()));
        assertEquals(0, AEROKV.countLiveKeysWithPrefix("g:" + lawn));

        deliverOutbox(orderId);
        assertTrue(outboxRepository.findAll().stream().anyMatch(e -> orderId.equals(e.getAggregateId()) && "OrderConfirmed".equals(e.getEventType())));

        // The seats are now sold: nobody can hold them again.
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isConflict());
    }

    @Test
    void aWebhookDeliveredThreeTimesCreatesExactlyOneOrder() throws Exception {
        JsonNode hold = hold(alice, ids("B1", "B2"), Map.of());
        UUID paymentId = UUID.fromString(startCheckout(alice, hold.get("holdToken").asText()).get("paymentId").asText());

        for (int i = 0; i < 3; i++) {
            webhookOk(paymentSucceeded(paymentId));
        }

        api.get(alice, "/api/orders?page=0&size=50").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        assertEquals(0, gateway.refunds.size());
    }

    @Test
    void sameWebhookArrivingConcurrentlyStillCreatesExactlyOneOrder() throws Exception {
        JsonNode hold = hold(alice, ids("B1", "B2"), Map.of());
        UUID paymentId = UUID.fromString(startCheckout(alice, hold.get("holdToken").asText()).get("paymentId").asText());

        int deliveries = 12;
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < deliveries; i++) {
            Callable<Integer> delivery = () -> {
                start.await();
                return paymentSucceeded(paymentId).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(delivery));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertEquals(200, result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        api.get(alice, "/api/orders?page=0&size=50").andExpect(jsonPath("$", hasSize(1)));
        assertEquals("CONFIRMED", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(0, gateway.refunds.size());
    }

    @Test
    void retryingCheckoutWithTheSameKeyReplaysTheAnswerAndNeverChargesTwice() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();

        JsonNode first = api.read(checkout(alice, "order-1-attempt", token).andExpect(status().isCreated()));
        checkout(alice, "order-1-attempt", token).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId", is(first.get("paymentId").asText())))
                .andExpect(jsonPath("$.clientSecret", is(first.get("clientSecret").asText())));
        assertEquals(1, gateway.created.size(), "the provider must be asked to create a payment only once");

        String otherToken = hold(alice, ids("B1", "B2"), Map.of()).get("holdToken").asText();
        checkout(alice, "order-1-attempt", otherToken).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

        checkout(alice, "a-different-key", token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CHECKOUT_ALREADY_STARTED")));
        assertEquals(1, gateway.created.size());
    }

    @Test
    void ifThePaymentProviderIsDownTheBuyerCanRetryTheSameHoldLater() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        gateway.failCreate = true;
        checkout(alice, "first-attempt-key", token).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code", is("PAYMENT_PROVIDER_ERROR")));

        gateway.failCreate = false;
        checkout(alice, "second-attempt-key", token).andExpect(status().isCreated());
        // Even the same key works again: a failed attempt is never remembered as the answer.
        assertEquals(1, gateway.created.size());
    }

    @Test
    void manyRequestsWithOneKeyAtTheSameInstantStartOnlyOnePayment() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        int callers = 15;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode[]>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<JsonNode[]> call = () -> {
                start.await();
                var response = checkout(alice, "shared-key-123", token).andReturn().getResponse();
                return new JsonNode[] {json.valueToTree(Map.of("status", response.getStatus())), json.readTree(response.getContentAsString())};
            };
            results.add(pool.submit(call));
        }
        start.countDown();

        java.util.Set<String> paymentIds = new java.util.HashSet<>();
        for (Future<JsonNode[]> result : results) {
            JsonNode[] outcome = result.get(30, TimeUnit.SECONDS);
            int code = outcome[0].get("status").asInt();
            assertTrue(code == 201 || code == 409, "unexpected status " + code);
            if (code == 201) {
                paymentIds.add(outcome[1].get("paymentId").asText());
            }
        }
        pool.shutdownNow();
        assertEquals(1, gateway.created.size(), "exactly one payment may be created");
        assertEquals(1, paymentIds.size(), "every successful answer must describe the same payment");
    }

    @Test
    void checkoutRefusesHoldsThatAreNotValidForThisBuyer() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();

        checkout(bob, "key-for-bob-1", token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("NOT_HOLD_OWNER")));
        checkout(alice, "key-alice-tampered", token.substring(0, token.length() - 3) + "xyz").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_HOLD_TOKEN")));
        checkout(alice, null, token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
        checkout(alice, "short", token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_INVALID")));
        mvc.perform(post("/api/checkout").contentType("application/json").content("{\"holdToken\":\"x\"}")).andExpect(status().isUnauthorized());

        api.post(alice, "/api/holds/release", Map.of("holdToken", token)).andExpect(status().isNoContent());
        checkout(alice, "key-after-release", token).andExpect(status().isGone()).andExpect(jsonPath("$.code", is("HOLD_EXPIRED")));
        assertEquals(0, gateway.created.size(), "no payment may be opened for a hold that is gone");
    }

    @Test
    void aFailedPaymentReleasesTheSeatsImmediatelyAndCancelsTheIntent() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of(lawn, 2)).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isConflict());

        webhookOk(webhook("failed", intentFor(paymentId), paymentId));

        JsonNode status = paymentStatus(alice, paymentId);
        assertEquals("FAILED", status.get("state").asText());
        assertEquals("Your card was declined.", status.get("failureReason").asText());
        assertEquals(List.of(intentFor(paymentId)), gateway.cancelled);
        assertEquals(0, AEROKV.countLiveKeysWithPrefix("g:" + lawn));
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());

        // A success notification for an already-failed payment must not resurrect it.
        webhookOk(paymentSucceeded(paymentId));
        assertEquals("FAILED", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(SeatAllocationStatus.AVAILABLE, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus());
    }

    @Test
    void ifStandingRoomSellsOutBeforePaymentLandsEverythingRollsBackAndTheBuyerIsRefunded() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of(lawn, 5)).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());

        // Meanwhile other buyers' tickets sell through the database counter until fewer than 5 remain.
        new TransactionTemplate(txManager).executeWithoutResult(s -> assertEquals(1, sectionRepository.reserveStandingRoom(lawn, 8)));

        webhookOk(paymentSucceeded(paymentId));

        assertEquals("REFUND_PENDING", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(SeatAllocationStatus.AVAILABLE, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus(),
                "seats must not stay booked when the rest of the order could not be completed");
        assertEquals(8, sectionRepository.findById(lawn).orElseThrow().getSoldQuantity());
        api.get(alice, "/api/orders").andExpect(jsonPath("$", hasSize(0)));

        deliverOutbox(paymentId.toString());
        assertEquals("REFUNDED", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(1, gateway.refunds.size());
        assertEquals(20_000, gateway.refunds.get(0).amountCents());
        assertEquals("refund-" + paymentId, gateway.refunds.get(0).idempotencyKey());
        assertEquals(RefundStatus.SUCCEEDED, refundRepository.findByPaymentTransactionId(paymentId).get(0).getStatus());
    }

    @Test
    void aRefundThatFailsIsRetriedWithBackoffAndIsNeverPaidTwice() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of(lawn, 5)).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());
        new TransactionTemplate(txManager).executeWithoutResult(s -> sectionRepository.reserveStandingRoom(lawn, 8));
        gateway.refundsToFail = 1;

        webhookOk(paymentSucceeded(paymentId));
        dispatcher.dispatchOnce();
        assertEquals("REFUND_PENDING", paymentStatus(alice, paymentId).get("state").asText(), "provider was down, so the refund is still owed");
        OutboxEventRecord waiting = outboxRepository.findAll().stream()
                .filter(e -> paymentId.toString().equals(e.getAggregateId())).findFirst().orElseThrow();
        assertEquals(1, waiting.getAttempts());
        assertEquals(null, waiting.getProcessedAt());

        // Retrying immediately does nothing (backed off); after the backoff it succeeds exactly once.
        dispatcher.dispatchOnce();
        assertEquals(0, gateway.refunds.size());
        Thread.sleep(2_300);
        deliverOutbox(paymentId.toString());
        deliverOutbox(paymentId.toString());
        assertEquals("REFUNDED", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(1, gateway.refunds.size());
    }

    @Test
    void anAbandonedCheckoutIsCancelledByTheSweepSoItCanNeverBePaidLate() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());

        expiry.abandonCheckouts();
        assertEquals("PENDING", paymentStatus(alice, paymentId).get("state").asText(), "a fresh checkout must be left alone");

        clock.advance(Duration.ofMinutes(20));
        assertTrue(expiry.abandonCheckouts() >= 1);

        JsonNode status = paymentStatus(alice, paymentId);
        assertEquals("FAILED", status.get("state").asText());
        assertEquals("checkout abandoned", status.get("failureReason").asText());
        assertTrue(gateway.cancelled.contains(intentFor(paymentId)), "the unpaid intent must be cancelled at the provider");
        assertEquals(0, expiry.abandonCheckouts(), "sweeping twice must not touch it again");
    }

    @Test
    void theSweepLeavesAPaymentAloneWhenTheProviderCannotCancelItBecauseItWasJustPaid() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());
        gateway.failCancel = true;
        clock.advance(Duration.ofMinutes(20));

        expiry.abandonCheckouts();
        assertEquals(PaymentTransactionState.PENDING, paymentRepository.findById(paymentId).orElseThrow().getState());
        assertFalse(gateway.cancelled.contains(intentFor(paymentId)));
    }

    @Test
    void webhooksAreRejectedWithoutAValidSignatureAndUnknownOnesAreAcknowledged() throws Exception {
        mvc.perform(post("/api/webhooks/stripe").header("Stripe-Signature", "wrong").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_SIGNATURE")));
        mvc.perform(post("/api/webhooks/stripe").contentType("application/json").content("{}")).andExpect(status().isBadRequest());

        // A perfectly signed event about a payment we have never heard of, and one of a type we ignore: both acknowledged (2xx),
        // otherwise the provider would keep retrying them for days.
        webhookOk(webhook("succeeded", "pi_never_seen", null));
        webhookOk(webhook("something-else", "pi_never_seen", null));
    }

    @Test
    void ordersArePrivateToTheirOwner() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());
        webhookOk(paymentSucceeded(paymentId));
        String orderId = paymentStatus(alice, paymentId).get("orderId").asText();

        api.get(bob, "/api/orders/" + orderId).andExpect(status().isNotFound());
        api.get(bob, "/api/checkout/" + paymentId).andExpect(status().isNotFound());
        api.get(bob, "/api/orders").andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/orders/" + orderId)).andExpect(status().isUnauthorized());
    }

    @Test
    void aPaymentBelowTheProvidersMinimumIsRejectedBeforeAnyChargeIsAttempted() throws Exception {
        User boss = api.newUser("ORGANIZER");
        JsonNode cheap = api.read(api.post(boss, "/api/events", Map.of(
                "name", "Pay What You Can", "venue", "Hall", "startsAt", java.time.Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS).toString(),
                "seatingMode", "GENERAL_ADMISSION",
                "sections", List.of(Map.of("name", "Floor", "kind", "GENERAL_ADMISSION", "currency", "USD", "priceCents", 10, "capacity", 20))))
                .andExpect(status().isCreated()));
        api.post(boss, "/api/events/" + cheap.get("id").asText() + "/publish", null).andExpect(status().isOk());
        UUID floor = api.sectionId(cheap, "Floor");

        String token = api.read(api.hold(alice, cheap, List.of(), Map.of(floor, 1)).andExpect(status().isCreated())).get("holdToken").asText();
        checkout(alice, "key-cheap-ticket", token).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("AMOUNT_BELOW_MINIMUM")));
        assertFalse(gateway.created.stream().anyMatch(c -> c.amountCents() == 10));
    }
}
