package com.holdlatch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.support.AbstractSettlementTest;
import com.holdlatch.support.FakePaymentConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** With no grace period, a payment that lands even a moment after the hold expired is refunded - the policy is a hard cutoff. */
@SpringBootTest(properties = {"holdlatch.hold.ttl=PT2S", "holdlatch.hold.payment-grace=PT0S"})
@AutoConfigureMockMvc
@Import(FakePaymentConfig.class)
class LatePaymentBeyondGraceIntegrationTest extends AbstractSettlementTest {

    @Test
    void aPaymentAfterTheGracePeriodIsRefundedEvenThoughTheSeatsAreStillFree() throws Exception {
        String token = hold(alice, ids("A1", "A2"), Map.of()).get("holdToken").asText();
        UUID paymentId = UUID.fromString(startCheckout(alice, token).get("paymentId").asText());

        Thread.sleep(2_500);
        webhookOk(paymentSucceeded(paymentId));

        assertEquals("REFUND_PENDING", paymentStatus(alice, paymentId).get("state").asText());
        assertEquals(SeatAllocationStatus.AVAILABLE, seatRepository.findById(seats.get("A1")).orElseThrow().getStatus());
        deliverOutbox(paymentId.toString());
        assertEquals("REFUNDED", paymentStatus(alice, paymentId).get("state").asText());
        api.hold(bob, event, ids("A1", "A2"), Map.of()).andExpect(status().isCreated());
    }
}
