package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.EventStatus;
import com.holdlatch.model.domain.SeatingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class EventRecord {

    @Id
    private UUID id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "seating_mode", nullable = false, updatable = false)
    private SeatingMode seatingMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected EventRecord() {}

    public EventRecord(UUID organizerId, String name, String venue, Instant startsAt, SeatingMode seatingMode, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.organizerId = organizerId;
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.seatingMode = seatingMode;
        this.status = EventStatus.DRAFT;
        this.createdAt = createdAt;
    }

    public void putOnSale() {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT event can be put on sale, was " + status);
        }
        status = EventStatus.ON_SALE;
    }

    public void close() {
        status = EventStatus.CLOSED;
    }

    public boolean isOnSale() {
        return status == EventStatus.ON_SALE;
    }

    public UUID getId() { return id; }
    public UUID getOrganizerId() { return organizerId; }
    public String getName() { return name; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public SeatingMode getSeatingMode() { return seatingMode; }
    public EventStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
