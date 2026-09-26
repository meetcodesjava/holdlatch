package com.holdlatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.repository.PersistentEventRepository;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CatalogCacheTest {

    private final UUID event = UUID.randomUUID();
    private final UUID section = UUID.randomUUID();

    private CatalogCache cacheWith(Duration ttl, AtomicInteger loads) {
        PersistentSeatRepository seatRepository = mock(PersistentSeatRepository.class);
        when(seatRepository.findBySectionIdOrderByRowLabelAscSeatNumberAsc(any())).thenAnswer(call -> {
            loads.incrementAndGet();
            Thread.sleep(150); // a slow database: the window in which a stampede would pile up
            return List.of(new SeatRecord(event, section, "A", 1), new SeatRecord(event, section, "A", 2));
        });
        HoldProperties props = new HoldProperties("x".repeat(40), Duration.ofMinutes(5), 8, true, 6, ttl, Duration.ofMinutes(2));
        return new CatalogCache(mock(PersistentEventRepository.class), mock(PersistentSectionRepository.class), seatRepository, props);
    }

    @Test
    void aHundredSimultaneousMissesCauseExactlyOneDatabaseLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CatalogCache cache = cacheWith(Duration.ofSeconds(30), loads);

        int callers = 100;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CatalogCache.SectionSeats>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.seats(section);
            }));
        }
        start.countDown();
        CatalogCache.SectionSeats first = results.get(0).get(10, TimeUnit.SECONDS);
        for (Future<CatalogCache.SectionSeats> result : results) {
            assertSame(first, result.get(10, TimeUnit.SECONDS), "everyone must receive the one loaded result");
        }
        pool.shutdownNow();

        assertEquals(1, loads.get(), "the herd must not stampede the database");
        assertEquals(2, first.seats().size());
        assertEquals(SeatAllocationStatus.AVAILABLE, first.seats().get(0).status());
    }

    @Test
    void entriesAreServedFromMemoryUntilTheyExpireThenReloaded() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CatalogCache cache = cacheWith(Duration.ofMillis(400), loads);

        cache.seats(section);
        cache.seats(section);
        cache.seats(section);
        assertEquals(1, loads.get());

        Thread.sleep(600);
        cache.seats(section);
        assertEquals(2, loads.get(), "after the TTL the data must be refreshed, so staleness stays bounded");
    }

    @Test
    void indexesSeatsByIdAndByRow() throws Exception {
        CatalogCache cache = cacheWith(Duration.ofSeconds(30), new AtomicInteger());
        CatalogCache.SectionSeats seats = cache.seats(section);
        assertEquals(2, seats.byId().size());
        assertEquals(2, seats.byRow().get("A").size());
    }
}
