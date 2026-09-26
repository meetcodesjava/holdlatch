package com.holdlatch.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** The real clock plus an offset a test can push forward, to make "ten minutes later" happen instantly. */
public class MutableClock extends Clock {

    private final AtomicLong offsetMillis = new AtomicLong();

    public void advance(Duration duration) {
        offsetMillis.addAndGet(duration.toMillis());
    }

    public void reset() {
        offsetMillis.set(0);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plusMillis(offsetMillis.get());
    }
}
