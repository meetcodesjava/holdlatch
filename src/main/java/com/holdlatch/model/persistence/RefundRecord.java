package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.RefundStatus;
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

/** Money returned to a customer, e.g. when their payment cleared after the seat hold had already expired. */
@Entity
@Table(name = "refunds")
public class RefundRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "payment_transaction_id", nullable = false, updatable = false)
    private UUID paymentTransactionId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "stripe_refund_id")
    private String stripeRefundId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected RefundRecord() {}

    public RefundRecord(UUID userId, UUID paymentTransactionId, long amountCents, String currency, String reason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.paymentTransactionId = paymentTransactionId;
        this.amountCents = amountCents;
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.reason = reason;
        this.status = RefundStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markSucceeded(String stripeRefundId) {
        this.status = RefundStatus.SUCCEEDED;
        this.stripeRefundId = stripeRefundId;
    }

    public void markFailed() {
        this.status = RefundStatus.FAILED;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getPaymentTransactionId() { return paymentTransactionId; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public String getStripeRefundId() { return stripeRefundId; }
    public Instant getCreatedAt() { return createdAt; }
}
