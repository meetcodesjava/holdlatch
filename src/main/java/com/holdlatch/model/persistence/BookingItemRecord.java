package com.holdlatch.model.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** One line of an order: a specific seat (quantity 1), or a quantity of standing-room tickets in a section. */
@Entity
@Table(name = "booking_items")
public class BookingItemRecord {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "seat_id", updatable = false)
    private UUID seatId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false, updatable = false)
    private long unitPriceCents;

    @Version
    private Long version;

    protected BookingItemRecord() {}

    private BookingItemRecord(UUID orderId, UUID sectionId, UUID seatId, int quantity, long unitPriceCents) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.sectionId = sectionId;
        this.seatId = seatId;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
    }

    public static BookingItemRecord forSeat(UUID orderId, UUID sectionId, UUID seatId, long unitPriceCents) {
        return new BookingItemRecord(orderId, sectionId, seatId, 1, unitPriceCents);
    }

    public static BookingItemRecord forStandingRoom(UUID orderId, UUID sectionId, int quantity, long unitPriceCents) {
        return new BookingItemRecord(orderId, sectionId, null, quantity, unitPriceCents);
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getSectionId() { return sectionId; }
    public UUID getSeatId() { return seatId; }
    public int getQuantity() { return quantity; }
    public long getUnitPriceCents() { return unitPriceCents; }
}
