package com.holdlatch.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.domain.SeatingMode;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.BookingItemRecord;
import com.holdlatch.model.persistence.BookingOrderRecord;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.IdempotencyRecord;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.model.persistence.UserRecord;
import com.holdlatch.support.AbstractPostgresTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceLayerTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired PersistentUserRepository users;
    @Autowired PersistentEventRepository events;
    @Autowired PersistentSectionRepository sections;
    @Autowired PersistentSeatRepository seats;
    @Autowired PersistentBookingRepository orders;
    @Autowired PersistentBookingItemRepository items;
    @Autowired PersistentPaymentTransactionRepository payments;
    @Autowired IdempotencyLogRepository idempotency;
    @Autowired OutboxRepository outbox;

    private UserRecord newUser(String email) {
        return users.saveAndFlush(new UserRecord(email, "hash", "Name", UserRole.CUSTOMER, NOW));
    }

    private EventRecord newEvent(UserRecord organizer) {
        return events.saveAndFlush(new EventRecord(organizer.getId(), "Concert", "Arena", NOW.plusSeconds(86400), SeatingMode.HYBRID, NOW));
    }

    private SectionRecord newSection(EventRecord event, String name, SectionKind kind, int capacity) {
        return sections.saveAndFlush(new SectionRecord(event.getId(), name, kind, capacity, 2500, "usd", NOW));
    }

    private BookingOrderRecord newOrder(UserRecord user, EventRecord event, String holdId) {
        PaymentTransactionRecord payment = payments.saveAndFlush(new PaymentTransactionRecord(user.getId(), event.getId(), holdId, 2500, "USD", NOW));
        return orders.saveAndFlush(new BookingOrderRecord(user.getId(), event.getId(), payment.getId(), 2500, "USD", NOW));
    }

    @Test
    void emailsAreUniqueIgnoringCase() {
        newUser("Fan@Example.com");
        assertTrue(users.existsByEmailIgnoreCase("fan@example.COM"));
        assertEquals("fan@example.com", users.findByEmailIgnoreCase("FAN@example.com").orElseThrow().getEmail());
        assertThrows(DataIntegrityViolationException.class, () -> newUser("fan@example.com"));
    }

    @Test
    void seatsCanBeListedInRowOrder() {
        EventRecord event = newEvent(newUser("org@example.com"));
        SectionRecord section = newSection(event, "Floor", SectionKind.ASSIGNED, 3);
        seats.saveAllAndFlush(List.of(
                new SeatRecord(event.getId(), section.getId(), "B", 1),
                new SeatRecord(event.getId(), section.getId(), "A", 2),
                new SeatRecord(event.getId(), section.getId(), "A", 1)));

        List<SeatRecord> ordered = seats.findBySectionIdOrderByRowLabelAscSeatNumberAsc(section.getId());
        assertEquals(List.of("A1", "A2", "B1"), ordered.stream().map(s -> s.getRowLabel() + s.getSeatNumber()).toList());
    }

    @Test
    void sameSeatPositionCannotExistTwiceInASection() {
        EventRecord event = newEvent(newUser("org@example.com"));
        SectionRecord section = newSection(event, "Floor", SectionKind.ASSIGNED, 3);
        seats.saveAndFlush(new SeatRecord(event.getId(), section.getId(), "A", 1));
        assertThrows(DataIntegrityViolationException.class,
                () -> seats.saveAndFlush(new SeatRecord(event.getId(), section.getId(), "A", 1)));
    }

    @Test
    void conditionalBookingUpdateOnlyFlipsSeatsThatAreStillAvailable() {
        EventRecord event = newEvent(newUser("org@example.com"));
        SectionRecord section = newSection(event, "Floor", SectionKind.ASSIGNED, 2);
        SeatRecord s1 = seats.saveAndFlush(new SeatRecord(event.getId(), section.getId(), "A", 1));
        SeatRecord s2 = seats.saveAndFlush(new SeatRecord(event.getId(), section.getId(), "A", 2));

        assertEquals(1, seats.markBookedIfAvailable(List.of(s1.getId())));
        // A second buyer asking for both seats only gets one: the caller sees 1 < 2 and must abort.
        assertEquals(1, seats.markBookedIfAvailable(List.of(s1.getId(), s2.getId())));
        assertEquals(0, seats.markBookedIfAvailable(List.of(s1.getId(), s2.getId())));
        assertEquals(2, seats.countBySectionIdAndStatus(section.getId(), SeatAllocationStatus.BOOKED));
    }

    @Test
    void aSeatCanNeverAppearOnTwoOrders() {
        UserRecord user = newUser("fan@example.com");
        EventRecord event = newEvent(newUser("org@example.com"));
        SectionRecord section = newSection(event, "Floor", SectionKind.ASSIGNED, 1);
        SeatRecord seat = seats.saveAndFlush(new SeatRecord(event.getId(), section.getId(), "A", 1));

        BookingOrderRecord first = newOrder(user, event, "hold-1");
        items.saveAndFlush(BookingItemRecord.forSeat(first.getId(), section.getId(), seat.getId(), 2500));

        BookingOrderRecord second = newOrder(user, event, "hold-2");
        assertThrows(DataIntegrityViolationException.class,
                () -> items.saveAndFlush(BookingItemRecord.forSeat(second.getId(), section.getId(), seat.getId(), 2500)));
    }

    @Test
    void standingRoomCountIgnoresRefundedOrders() {
        UserRecord user = newUser("fan@example.com");
        EventRecord event = newEvent(newUser("org@example.com"));
        SectionRecord lawn = newSection(event, "Lawn", SectionKind.GENERAL_ADMISSION, 100);

        BookingOrderRecord kept = newOrder(user, event, "hold-1");
        items.saveAndFlush(BookingItemRecord.forStandingRoom(kept.getId(), lawn.getId(), 4, 2500));
        BookingOrderRecord refunded = newOrder(user, event, "hold-2");
        items.saveAndFlush(BookingItemRecord.forStandingRoom(refunded.getId(), lawn.getId(), 6, 2500));
        refunded.markRefunded();
        orders.saveAndFlush(refunded);

        assertEquals(4, items.confirmedQuantityForSection(lawn.getId()));
    }

    @Test
    void oneIdempotencyKeyPerUserIsEnforced() {
        UserRecord user = newUser("fan@example.com");
        UserRecord other = newUser("other@example.com");
        idempotency.saveAndFlush(new IdempotencyRecord(user.getId(), "key-1", "hash", NOW));
        // The same key is fine for a different user.
        idempotency.saveAndFlush(new IdempotencyRecord(other.getId(), "key-1", "hash", NOW));

        IdempotencyRecord stored = idempotency.findByUserIdAndIdemKey(user.getId(), "key-1").orElseThrow();
        assertEquals(false, stored.isCompleted());
        stored.complete(201, "{\"ok\":true}");
        idempotency.saveAndFlush(stored);
        assertTrue(idempotency.findByUserIdAndIdemKey(user.getId(), "key-1").orElseThrow().isCompleted());

        // A violation aborts the surrounding transaction, so it must be the last step.
        assertThrows(DataIntegrityViolationException.class,
                () -> idempotency.saveAndFlush(new IdempotencyRecord(user.getId(), "key-1", "hash", NOW)));
    }

    @Test
    void paymentIntentIdsAreUniqueAndLookupWorks() {
        UserRecord user = newUser("fan@example.com");
        EventRecord event = newEvent(newUser("org@example.com"));
        PaymentTransactionRecord a = new PaymentTransactionRecord(user.getId(), event.getId(), "hold-1", 100, "USD", NOW);
        a.attachPaymentIntent("pi_123");
        payments.saveAndFlush(a);

        assertEquals(PaymentTransactionState.PENDING, payments.findByStripePaymentIntentId("pi_123").orElseThrow().getState());
        assertEquals(a.getId(), payments.findByStripePaymentIntentIdForUpdate("pi_123").orElseThrow().getId());

        PaymentTransactionRecord b = new PaymentTransactionRecord(user.getId(), event.getId(), "hold-2", 100, "USD", NOW);
        b.attachPaymentIntent("pi_123");
        assertThrows(DataIntegrityViolationException.class, () -> payments.saveAndFlush(b));
    }

    @Test
    void outboxClaimsOnlyDueUnprocessedEventsAndStoresJson() {
        OutboxEventRecord due = outbox.saveAndFlush(new OutboxEventRecord("Order", "1", "OrderConfirmed", "{\"orderId\":\"1\"}", NOW));
        OutboxEventRecord later = new OutboxEventRecord("Order", "2", "OrderConfirmed", "{}", NOW);
        later.markFailed(NOW);
        outbox.saveAndFlush(later);
        OutboxEventRecord done = new OutboxEventRecord("Order", "3", "OrderConfirmed", "{}", NOW);
        done.markProcessed(NOW);
        outbox.saveAndFlush(done);

        List<OutboxEventRecord> claimed = outbox.claimDueBatch(NOW, 10);
        assertEquals(1, claimed.size());
        assertEquals(due.getId(), claimed.get(0).getId());
        assertTrue(claimed.get(0).getPayload().contains("orderId"));

        // Ten seconds later the backed-off event is due again; the processed one never is.
        assertEquals(2, outbox.claimDueBatch(NOW.plusSeconds(10), 10).size());
    }
}
