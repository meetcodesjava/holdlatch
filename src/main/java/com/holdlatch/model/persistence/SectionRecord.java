package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.SectionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A priced area of an event: either numbered seats or a standing-room pool with a fixed capacity. */
@Entity
@Table(name = "sections")
public class SectionRecord {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SectionKind kind;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected SectionRecord() {}

    public SectionRecord(UUID eventId, String name, SectionKind kind, int capacity, long priceCents, String currency, Instant createdAt) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.name = name;
        this.kind = kind;
        this.capacity = capacity;
        this.priceCents = priceCents;
        this.currency = currency.toUpperCase(java.util.Locale.ROOT);
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getName() { return name; }
    public SectionKind getKind() { return kind; }
    public int getCapacity() { return capacity; }
    public long getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
