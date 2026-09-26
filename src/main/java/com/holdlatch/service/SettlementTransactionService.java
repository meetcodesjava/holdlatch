package com.holdlatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.domain.RefundStatus;
import com.holdlatch.model.domain.ReservationHoldToken;
import com.holdlatch.model.persistence.BookingItemRecord;
import com.holdlatch.model.persistence.BookingOrderRecord;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.model.persistence.RefundRecord;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.repository.OutboxRepository;
import com.holdlatch.repository.PersistentBookingItemRepository;
import com.holdlatch.repository.PersistentBookingRepository;
import com.holdlatch.repository.PersistentPaymentTransactionRepository;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import com.holdlatch.repository.RefundRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database change that settlement makes, each as ONE transaction. Each
 * method first row-locks the payment, so two deliveries of the same webhook
 * (or a webhook racing the abandoned-checkout sweep) are handled strictly one
 * after the other and the second one sees the first one's result.
 */
@Service
public class SettlementTransactionService {

    public enum Result { CONFIRMED, REFUND_SCHEDULED, DUPLICATE }

    /** Thrown inside confirm() to roll back every change it made; the caller then schedules a refund instead. */
    public static class SettlementRefusedException extends RuntimeException {
        public SettlementRefusedException(String message) {
            super(message);
        }
    }

    private final PersistentPaymentTransactionRepository payments;
    private final PersistentSeatRepository seats;
    private final PersistentSectionRepository sections;
    private final PersistentBookingRepository orders;
    private final PersistentBookingItemRepository items;
    private final RefundRepository refunds;
    private final OutboxRepository outbox;
    private final PaymentGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    SettlementTransactionService(PersistentPaymentTransactionRepository payments, PersistentSeatRepository seats,
                                 PersistentSectionRepository sections, PersistentBookingRepository orders,
                                 PersistentBookingItemRepository items, RefundRepository refunds, OutboxRepository outbox,
                                 PaymentGateway gateway, ObjectMapper objectMapper, Clock clock) {
        this.payments = payments;
        this.seats = seats;
        this.sections = sections;
        this.orders = orders;
        this.items = items;
        this.refunds = refunds;
        this.outbox = outbox;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Payment succeeded and the hold is secured: book the seats, count the
     * standing tickets, write the order and the outbox event - atomically.
     * If any guard refuses, everything is rolled back and SettlementRefusedException is thrown.
     */
    @Transactional
    public Result confirm(UUID paymentId, ReservationHoldToken token) {
        PaymentTransactionRecord locked = lock(paymentId);
        if (locked.getState() != PaymentTransactionState.PENDING) {
            return Result.DUPLICATE;
        }

        List<UUID> seatIds = token.seatIds();
        if (!seatIds.isEmpty() && seats.markBookedIfAvailable(seatIds) != seatIds.size()) {
            throw new SettlementRefusedException("one or more seats were no longer available");
        }
        for (ReservationHoldToken.StandingLine line : token.standing()) {
            if (sections.reserveStandingRoom(line.sectionId(), line.quantity()) != 1) {
                throw new SettlementRefusedException("standing room in a section sold out before payment completed");
            }
        }

        // The bulk updates above clear the persistence context; reload so the state change below is tracked.
        PaymentTransactionRecord payment = payments.findById(paymentId).orElseThrow();
        Instant now = clock.instant();
        payment.transitionTo(PaymentTransactionState.SUCCEEDED, now);
        payment.transitionTo(PaymentTransactionState.CONFIRMED, now);

        BookingOrderRecord order = orders.save(new BookingOrderRecord(payment.getUserId(), payment.getEventId(), paymentId,
                token.amountCents(), token.currency(), now));
        long computedTotal = writeItems(order, token);
        if (computedTotal != token.amountCents()) {
            throw new SettlementRefusedException("the priced total no longer matches the sections' prices");
        }

        outbox.save(new OutboxEventRecord("Order", order.getId().toString(), "OrderConfirmed", json(Map.of(
                "orderId", order.getId(), "userId", payment.getUserId(), "eventId", payment.getEventId(),
                "totalCents", token.amountCents(), "currency", token.currency())), now));
        return Result.CONFIRMED;
    }

    /** Money was taken but the seats cannot be given: record it and queue the refund (executed reliably by the outbox worker). */
    @Transactional
    public Result scheduleRefund(UUID paymentId, String reason) {
        PaymentTransactionRecord payment = lock(paymentId);
        if (payment.getState() != PaymentTransactionState.PENDING) {
            return Result.DUPLICATE;
        }
        Instant now = clock.instant();
        payment.transitionTo(PaymentTransactionState.SUCCEEDED, now);
        payment.transitionTo(PaymentTransactionState.REFUND_PENDING, now);
        refunds.save(new RefundRecord(payment.getUserId(), paymentId, payment.getAmountCents(), payment.getCurrency(),
                reason.substring(0, Math.min(reason.length(), 200)), now));
        outbox.save(new OutboxEventRecord("Payment", paymentId.toString(), "RefundRequested",
                json(Map.of("paymentTransactionId", paymentId)), now));
        return Result.REFUND_SCHEDULED;
    }

    /** Called by the outbox worker. Safe to run twice: only a payment still waiting for its refund is refunded. */
    @Transactional
    public void executeRefund(UUID paymentId) {
        PaymentTransactionRecord payment = lock(paymentId);
        if (payment.getState() != PaymentTransactionState.REFUND_PENDING) {
            return;
        }
        RefundRecord refund = refunds.findByPaymentTransactionId(paymentId).stream()
                .filter(r -> r.getStatus() == RefundStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pending refund row for payment " + paymentId));
        String providerRefundId = gateway.refund(payment.getStripePaymentIntentId(), payment.getAmountCents(), "refund-" + paymentId);
        refund.markSucceeded(providerRefundId);
        payment.transitionTo(PaymentTransactionState.REFUNDED, clock.instant());
    }

    /** Marks a still-unpaid payment as failed. Returns false if it had already moved on (e.g. it was paid meanwhile). */
    @Transactional
    public boolean markFailed(UUID paymentId, String reason) {
        PaymentTransactionRecord payment = lock(paymentId);
        if (payment.getState() != PaymentTransactionState.PENDING) {
            return false;
        }
        payment.fail(reason, clock.instant());
        return true;
    }

    private PaymentTransactionRecord lock(UUID paymentId) {
        return payments.findByIdForUpdate(paymentId).orElseThrow(() -> new IllegalStateException("Unknown payment " + paymentId));
    }

    private long writeItems(BookingOrderRecord order, ReservationHoldToken token) {
        long total = 0;
        if (!token.seatIds().isEmpty()) {
            List<SeatRecord> booked = seats.findAllById(token.seatIds());
            Map<UUID, SectionRecord> sectionById = sections.findAllById(booked.stream().map(SeatRecord::getSectionId).distinct().toList())
                    .stream().collect(Collectors.toMap(SectionRecord::getId, Function.identity()));
            for (SeatRecord seat : booked) {
                long price = sectionById.get(seat.getSectionId()).getPriceCents();
                items.save(BookingItemRecord.forSeat(order.getId(), seat.getSectionId(), seat.getId(), price));
                total += price;
            }
        }
        for (ReservationHoldToken.StandingLine line : token.standing()) {
            items.save(BookingItemRecord.forStandingRoom(order.getId(), line.sectionId(), line.quantity(), line.unitPriceCents()));
            total += line.unitPriceCents() * line.quantity();
        }
        return total;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }
    }
}
