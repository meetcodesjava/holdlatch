package com.holdlatch.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.model.domain.EventStatus;
import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.domain.SeatingMode;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.model.persistence.UserRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void seatingModeDecidesWhichSectionKindsAnEventMayHave() {
        assertTrue(SeatingMode.ASSIGNED.allows(SectionKind.ASSIGNED));
        assertFalse(SeatingMode.ASSIGNED.allows(SectionKind.GENERAL_ADMISSION));
        assertTrue(SeatingMode.GENERAL_ADMISSION.allows(SectionKind.GENERAL_ADMISSION));
        assertFalse(SeatingMode.GENERAL_ADMISSION.allows(SectionKind.ASSIGNED));
        assertTrue(SeatingMode.HYBRID.allows(SectionKind.ASSIGNED));
        assertTrue(SeatingMode.HYBRID.allows(SectionKind.GENERAL_ADMISSION));
    }

    @Test
    void paymentStateMachineOnlyAllowsTheDocumentedPaths() {
        assertTrue(PaymentTransactionState.PENDING.canTransitionTo(PaymentTransactionState.SUCCEEDED));
        assertTrue(PaymentTransactionState.SUCCEEDED.canTransitionTo(PaymentTransactionState.CONFIRMED));
        assertTrue(PaymentTransactionState.SUCCEEDED.canTransitionTo(PaymentTransactionState.REFUND_PENDING));
        assertTrue(PaymentTransactionState.REFUND_PENDING.canTransitionTo(PaymentTransactionState.REFUNDED));

        assertFalse(PaymentTransactionState.PENDING.canTransitionTo(PaymentTransactionState.CONFIRMED));
        assertFalse(PaymentTransactionState.CONFIRMED.canTransitionTo(PaymentTransactionState.REFUND_PENDING));
        assertFalse(PaymentTransactionState.FAILED.canTransitionTo(PaymentTransactionState.SUCCEEDED));
        assertTrue(PaymentTransactionState.REFUNDED.allowedNext().isEmpty());
    }

    @Test
    void paymentRecordRejectsIllegalTransitionsAndKeepsFailureReasonBounded() {
        PaymentTransactionRecord payment = new PaymentTransactionRecord(UUID.randomUUID(), UUID.randomUUID(), "hold-1", "{}", 5000, "usd", NOW);
        assertEquals("USD", payment.getCurrency());
        assertEquals(PaymentTransactionState.PENDING, payment.getState());

        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentTransactionState.CONFIRMED, NOW));
        payment.fail("x".repeat(900), NOW);
        assertEquals(PaymentTransactionState.FAILED, payment.getState());
        assertEquals(500, payment.getFailureReason().length());
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentTransactionState.SUCCEEDED, NOW));
    }

    @Test
    void eventCanOnlyGoOnSaleFromDraft() {
        EventRecord event = new EventRecord(UUID.randomUUID(), "Gig", "Hall", NOW, SeatingMode.HYBRID, NOW);
        assertEquals(EventStatus.DRAFT, event.getStatus());
        event.putOnSale();
        assertTrue(event.isOnSale());
        assertThrows(IllegalStateException.class, event::putOnSale);
    }

    @Test
    void seatCannotBeBookedTwice() {
        SeatRecord seat = new SeatRecord(UUID.randomUUID(), UUID.randomUUID(), "A", 1);
        seat.markBooked();
        assertThrows(IllegalStateException.class, seat::markBooked);
        seat.markAvailable();
        seat.markBooked();
    }

    @Test
    void userEmailIsNormalizedAndSectionRejectsBadValues() {
        assertEquals("a@b.com", new UserRecord("  A@B.com ", "hash", "A", UserRole.CUSTOMER, NOW).getEmail());
        assertThrows(IllegalArgumentException.class,
                () -> new SectionRecord(UUID.randomUUID(), "S", SectionKind.ASSIGNED, 0, 100, "USD", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new SectionRecord(UUID.randomUUID(), "S", SectionKind.ASSIGNED, 10, -1, "USD", NOW));
    }

    @Test
    void outboxRetryBacksOffExponentiallyAndIsCapped() {
        OutboxEventRecord event = new OutboxEventRecord("Order", "1", "OrderConfirmed", "{}", NOW);
        event.markFailed(NOW);
        assertEquals(NOW.plus(Duration.ofSeconds(2)), event.getNextAttemptAt());
        event.markFailed(NOW);
        assertEquals(NOW.plus(Duration.ofSeconds(4)), event.getNextAttemptAt());
        for (int i = 0; i < 20; i++) {
            event.markFailed(NOW);
        }
        assertEquals(NOW.plus(Duration.ofSeconds(256)), event.getNextAttemptAt());
    }
}
