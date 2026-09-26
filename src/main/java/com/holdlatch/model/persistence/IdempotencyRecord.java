package com.holdlatch.model.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Remembers the outcome of a request per (user, Idempotency-Key). A row with
 * no response yet means the first attempt is still in flight; a stored
 * response is replayed verbatim for any retry with the same key and payload.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idem_key", nullable = false, updatable = false)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(UUID userId, String idemKey, String requestHash, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public boolean isCompleted() {
        return responseStatus != null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIdemKey() { return idemKey; }
    public String getRequestHash() { return requestHash; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
