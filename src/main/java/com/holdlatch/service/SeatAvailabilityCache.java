package com.holdlatch.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.holdlatch.config.HoldProperties;
import com.holdlatch.dto.EventDtos.SeatView;
import com.holdlatch.repository.PersistentSeatRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Short-lived cache of a section's seat map. When a popular section is
 * requested by thousands of clients at once, exactly one of them loads it from
 * the database; the rest wait for that single result instead of stampeding the
 * database (the "thundering herd" problem).
 */
@Component
class SeatAvailabilityCache {

    private final LoadingCache<UUID, List<SeatView>> cache;

    SeatAvailabilityCache(PersistentSeatRepository seats, HoldProperties props) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(props.seatMapCacheTtl())
                .build(sectionId -> seats.findBySectionIdOrderByRowLabelAscSeatNumberAsc(sectionId).stream()
                        .map(s -> new SeatView(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getStatus()))
                        .toList());
    }

    List<SeatView> get(UUID sectionId) {
        return cache.get(sectionId);
    }
}
