package com.holdlatch.model.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An outbound event written in the same transaction as the state change that
 * caused it, then delivered later by a background worker. If the process dies
 * between "database committed" and "event sent", the row is still there.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventRecord {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Version
    private Long version;

    protected OutboxEventRecord() {}

    public OutboxEventRecord(String aggregateType, String aggregateId, String eventType, String payloadJson, Instant now) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payloadJson;
        this.createdAt = now;
        this.nextAttemptAt = now;
        this.attempts = 0;
    }

    public void markProcessed(Instant now) {
        this.processedAt = now;
    }

    /** Exponential backoff: 2s, 4s, 8s ... capped at 256s (about 4 minutes). */
    public void markFailed(Instant now) {
        this.attempts++;
        long seconds = 1L << Math.min(attempts, 8);
        this.nextAttemptAt = now.plus(Duration.ofSeconds(seconds));
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
