package com.holdlatch.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.holdlatch.config.HoldProperties;
import com.holdlatch.dto.EventDtos.SeatView;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.repository.PersistentEventRepository;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Short-lived, in-process copies of the catalog data a hold request needs
 * (event, sections, seat layout and status). This is what keeps the database
 * out of the hot path: a hold that AeroKV then rejects because the seat is
 * taken costs zero database queries, so thousands of buyers fighting over the
 * same seats cannot exhaust the connection pool.
 *
 * <p>Each cache coalesces concurrent loads ("singleflight"): when many
 * requests miss at once, exactly one loads from the database and the rest wait
 * for that result - the thundering-herd protection. Entries live for
 * {@code holdlatch.hold.catalog-cache-ttl} (1 second by default), so the
 * data can be at most that stale. That is safe because the database still has
 * the last word: seats are re-checked after a hold is acquired, and booking is
 * guarded by conditional updates at settlement.
 */
@Component
class CatalogCache {

    /** All seats of one section, indexed for the two lookups a hold needs. */
    record SectionSeats(UUID sectionId, List<SeatView> seats, Map<UUID, SeatView> byId, Map<String, List<SeatView>> byRow) {}

    // Which section a seat belongs to never changes, so this can be kept much longer than the mutable data.
    private static final Duration SEAT_INDEX_TTL = Duration.ofMinutes(10);

    private final LoadingCache<UUID, Optional<EventRecord>> events;
    private final LoadingCache<UUID, List<SectionRecord>> sections;
    private final LoadingCache<UUID, SectionSeats> seats;
    private final LoadingCache<UUID, Map<UUID, UUID>> seatToSection;

    CatalogCache(PersistentEventRepository eventRepository, PersistentSectionRepository sectionRepository,
                 PersistentSeatRepository seatRepository, HoldProperties props) {
        Duration ttl = props.catalogCacheTtl();
        this.events = Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(ttl).build(eventRepository::findById);
        this.sections = Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(ttl).build(sectionRepository::findByEventIdOrderByName);
        this.seats = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(ttl).build(sectionId -> {
            List<SeatView> views = seatRepository.findBySectionIdOrderByRowLabelAscSeatNumberAsc(sectionId).stream()
                    .map(s -> new SeatView(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getStatus()))
                    .toList();
            Map<UUID, SeatView> byId = new HashMap<>();
            Map<String, List<SeatView>> byRow = new HashMap<>();
            for (SeatView view : views) {
                byId.put(view.id(), view);
                byRow.computeIfAbsent(view.row(), r -> new java.util.ArrayList<>()).add(view);
            }
            return new SectionSeats(sectionId, views, byId, byRow);
        });
        this.seatToSection = Caffeine.newBuilder().maximumSize(100).expireAfterWrite(ttl.isZero() ? ttl : SEAT_INDEX_TTL).build(eventId -> {
            Map<UUID, UUID> index = new HashMap<>();
            seatRepository.findSeatSections(eventId).forEach(row -> index.put(row.getId(), row.getSectionId()));
            return index;
        });
    }

    Optional<EventRecord> event(UUID eventId) {
        return events.get(eventId);
    }

    List<SectionRecord> sections(UUID eventId) {
        return sections.get(eventId);
    }

    SectionSeats seats(UUID sectionId) {
        return seats.get(sectionId);
    }

    /** Which section each seat of the event belongs to. */
    Map<UUID, UUID> seatSections(UUID eventId) {
        return seatToSection.get(eventId);
    }

    /** Called when an event's own data changes on this instance (e.g. it goes on sale), so the change shows at once. */
    void invalidateEvent(UUID eventId) {
        events.invalidate(eventId);
        sections.invalidate(eventId);
    }
}
