package com.holdlatch.model.persistence;

import com.holdlatch.model.domain.SeatAllocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** One numbered seat. Only AVAILABLE/BOOKED are persisted; a hold is a short-lived AeroKV entry, not a row. */
@Entity
@Table(name = "seats")
public class SeatRecord {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "row_label", nullable = false, updatable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatAllocationStatus status;

    @Version
    private Long version;

    protected SeatRecord() {}

    public SeatRecord(UUID eventId, UUID sectionId, String rowLabel, int seatNumber) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.sectionId = sectionId;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.status = SeatAllocationStatus.AVAILABLE;
    }

    public void markBooked() {
        if (status != SeatAllocationStatus.AVAILABLE) {
            throw new IllegalStateException("Seat " + id + " is already " + status);
        }
        status = SeatAllocationStatus.BOOKED;
    }

    public void markAvailable() {
        status = SeatAllocationStatus.AVAILABLE;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getSectionId() { return sectionId; }
    public String getRowLabel() { return rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public SeatAllocationStatus getStatus() { return status; }
}
