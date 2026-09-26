package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.PaymentTransactionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** One payment attempt against one hold; its state machine is what keeps late or repeated payment callbacks safe. */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransactionRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private String holdId;

    @Column(name = "hold_snapshot", nullable = false, updatable = false)
    private String holdSnapshot;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentTransactionState state;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected PaymentTransactionRecord() {}

    public PaymentTransactionRecord(UUID userId, UUID eventId, String holdId, String holdSnapshot, long amountCents, String currency, Instant now) {
        if (amountCents < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.eventId = eventId;
        this.holdId = holdId;
        this.holdSnapshot = holdSnapshot;
        this.amountCents = amountCents;
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.state = PaymentTransactionState.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void attachPaymentIntent(String paymentIntentId) {
        this.stripePaymentIntentId = paymentIntentId;
    }

    public void transitionTo(PaymentTransactionState next, Instant now) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal payment transition " + state + " -> " + next);
        }
        this.state = next;
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        transitionTo(PaymentTransactionState.FAILED, now);
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getEventId() { return eventId; }
    public String getHoldId() { return holdId; }
    public String getHoldSnapshot() { return holdSnapshot; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public PaymentTransactionState getState() { return state; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
