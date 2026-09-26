package com.holdlatch.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.support.AbstractSettlementTest;
import com.holdlatch.support.ApiFixture.User;
import com.holdlatch.support.FakePaymentConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The "bank clears the payment right as the 5-minute timer runs out" problem, with holds shortened to
 * two seconds and a 30-second grace period so the real expiry can be waited out.
 */
@SpringBootTest(properties = {"holdlatch.hold.ttl=PT2S", "holdlatch.hold.payment-grace=PT30S"})
@AutoConfigureMockMvc
@Import(FakePaymentConfig.class)
class LatePaymentIntegrationTest extends AbstractSettlementTest {

    @Test
    void aPaymentLandingAfterExpiryStillWinsItsSeatsBackIfNobodyElseTookThem() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());

        Thread.sleep(2_500);
        assertEquals(0, AEROKV.countLiveKeysWithPrefix("s:" + event.get("id").asText()), "the hold has expired on its own");

        webhookOk(paymentSucceeded(paymentId));

        JsonNode status = paymentStatus(alice, paymentId);
        assertEquals("CONFIRMED", status.get("state").asText());
        api.get(alice, "/api/orders/" + status.get("orderId").asText()).andExpect(jsonPath("$.items", hasSize(2)));
        assertEquals(SeatAllocationStatus.BOOKED, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus());
        assertEquals(0, gateway.refunds.size());
    }

    @Test
    void aPaymentLandingAfterSomeoneElseTookTheSeatsIsRefundedAndTheNewHolderIsUntouched() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());

        Thread.sleep(2_500);
        User carol = api.newUser("CUSTOMER");
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());

        webhookOk(paymentSucceeded(paymentId));

        // Alice was charged but her seats now belong to Bob's live hold: refund her, never double-book.
        assertEquals("REFUND_PENDING", paymentStatus(alice, paymentId).get("state").asText());
        api.hold(carol, event, ids("A1", "A2"), Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SEAT_UNAVAILABLE")));
        assertEquals(SeatAllocationStatus.AVAILABLE, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus());

        deliverOutbox(paymentId.toString());
        assertEquals("REFUNDED", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(1, gateway.refunds.size());
        assertEquals(10_000, gateway.refunds.get(0).amountCents());
        api.get(alice, "/api/orders").andExpect(jsonPath("$", hasSize(0)));
    }
}
