package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.OrderStatus;
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

@Entity
@Table(name = "booking_orders")
public class BookingOrderRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "payment_transaction_id", nullable = false, updatable = false)
    private UUID paymentTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_cents", nullable = false, updatable = false)
    private long totalCents;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected BookingOrderRecord() {}

    public BookingOrderRecord(UUID userId, UUID eventId, UUID paymentTransactionId, long totalCents, String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.eventId = eventId;
        this.paymentTransactionId = paymentTransactionId;
        this.status = OrderStatus.CONFIRMED;
        this.totalCents = totalCents;
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.createdAt = createdAt;
    }

    public void markRefunded() {
        this.status = OrderStatus.REFUNDED;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getEventId() { return eventId; }
    public UUID getPaymentTransactionId() { return paymentTransactionId; }
    public OrderStatus getStatus() { return status; }
    public long getTotalCents() { return totalCents; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
