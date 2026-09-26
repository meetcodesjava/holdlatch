package com.holdlatch.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        TokenBucket bucket = new TokenBucket(3, 1.0, nanos::get);
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        TokenBucket bucket = new TokenBucket(2, 1.0, nanos::get);
        bucket.tryConsume();
        bucket.tryConsume();
        assertFalse(bucket.tryConsume());

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());

        nanos.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }

    @Test
    void reportsHowLongUntilTheNextTokenForRetryAfter() {
        TokenBucket bucket = new TokenBucket(1, 0.5, nanos::get);
        assertTrue(bucket.tryConsume());
        assertEquals(2, bucket.secondsUntilNextToken());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(1, bucket.secondsUntilNextToken());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(0, bucket.secondsUntilNextToken());
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1.0, nanos::get));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0.0, nanos::get));
    }
}
