package com.holdlatch.security;

import java.util.function.LongSupplier;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, refilled
 * continuously at {@code refillPerSecond}. Each request spends one token; an
 * empty bucket means "too many requests". Bursts up to capacity are allowed,
 * sustained traffic is held to the refill rate.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;

    private double tokens;
    private long lastRefillNanos;

    TokenBucket(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /** Spends one token if available; returns false (spending nothing) when the bucket is empty. */
    synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Whole seconds until at least one token will be available (minimum 1), for the Retry-After header. */
    synchronized long secondsUntilNextToken() {
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        double nanosNeeded = (1.0 - tokens) / refillPerNano;
        return Math.max(1L, (long) Math.ceil(nanosNeeded / 1_000_000_000.0));
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
    }
}
