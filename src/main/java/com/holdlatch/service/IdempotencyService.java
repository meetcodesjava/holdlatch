package com.holdlatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.exception.ApiException;
import com.holdlatch.model.persistence.IdempotencyRecord;
import com.holdlatch.repository.IdempotencyLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a request safe to retry: the first call with a given Idempotency-Key
 * does the work and remembers the answer; any repeat with the same key and
 * payload gets that same answer back without doing the work again. Only
 * successes are remembered - a failed attempt frees the key so the client can
 * simply try again.
 */
@Service
public class IdempotencyService {

    public record Result<T>(T body, boolean replayed) {}

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{8,200}");
    // An in-flight marker this old means the server died mid-request; the key is taken over rather than stuck forever.
    private static final Duration STALE_IN_PROGRESS = Duration.ofMinutes(5);

    private final IdempotencyLogRepository records;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    IdempotencyService(IdempotencyLogRepository records, PlatformTransactionManager txManager, ObjectMapper objectMapper, Clock clock) {
        this.records = records;
        this.tx = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> Result<T> execute(UUID userId, String key, String requestHash, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "The Idempotency-Key header is required.");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-200 characters from A-Z a-z 0-9 . _ : -");
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            UUID recordId = tryClaim(userId, key, requestHash);
            if (recordId != null) {
                return runAndRemember(recordId, action);
            }
            IdempotencyRecord existing = records.findByUserIdAndIdemKey(userId, key).orElse(null);
            if (existing == null) {
                continue;
            }
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
            }
            if (existing.isCompleted()) {
                return new Result<>(read(existing.getResponseBody(), type), true);
            }
            if (existing.getCreatedAt().plus(STALE_IN_PROGRESS).isBefore(clock.instant())) {
                tx.executeWithoutResult(s -> records.deleteById(existing.getId()));
                continue;
            }
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "The original request is still being processed.");
        }
        throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "The original request is still being processed.");
    }

    /** Inserts the in-flight marker; the unique (user, key) index makes exactly one concurrent caller win. */
    private UUID tryClaim(UUID userId, String key, String requestHash) {
        return tx.execute(status -> {
            try {
                return records.saveAndFlush(new IdempotencyRecord(userId, key, requestHash, clock.instant())).getId();
            } catch (DataIntegrityViolationException e) {
                status.setRollbackOnly();
                return null;
            }
        });
    }

    private <T> Result<T> runAndRemember(UUID recordId, Supplier<T> action) {
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> records.deleteById(recordId));
            throw e;
        }
        String body = write(result);
        tx.executeWithoutResult(s -> records.findById(recordId).ifPresent(r -> {
            r.complete(HttpStatus.CREATED.value(), body);
            records.save(r);
        }));
        return new Result<>(result, false);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store idempotent response", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }
}
