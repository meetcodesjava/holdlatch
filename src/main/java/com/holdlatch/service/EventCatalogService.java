package com.holdlatch.service;

import com.holdlatch.dto.EventDtos.CreateEventRequest;
import com.holdlatch.dto.EventDtos.EventDetail;
import com.holdlatch.dto.EventDtos.EventSummary;
import com.holdlatch.dto.EventDtos.PagedResponse;
import com.holdlatch.dto.EventDtos.RowSpec;
import com.holdlatch.dto.EventDtos.SeatView;
import com.holdlatch.dto.EventDtos.SectionSpec;
import com.holdlatch.dto.EventDtos.SectionView;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.exception.NotFoundException;
import com.holdlatch.model.domain.EventStatus;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.model.persistence.SectionRecord;
import com.holdlatch.repository.PersistentEventRepository;
import com.holdlatch.repository.PersistentSeatRepository;
import com.holdlatch.repository.PersistentSectionRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organizers build events (with their seating layout); customers browse them. */
@Service
public class EventCatalogService {

    private static final int MAX_SEATS_PER_SECTION = 20_000;

    private final PersistentEventRepository events;
    private final PersistentSectionRepository sections;
    private final PersistentSeatRepository seats;
    private final CatalogCache catalogCache;
    private final Clock clock;

    EventCatalogService(PersistentEventRepository events, PersistentSectionRepository sections, PersistentSeatRepository seats,
                        CatalogCache catalogCache, Clock clock) {
        this.events = events;
        this.sections = sections;
        this.seats = seats;
        this.catalogCache = catalogCache;
        this.clock = clock;
    }

    @Transactional
    public EventDetail createEvent(UUID organizerId, CreateEventRequest request) {
        if (!request.startsAt().isAfter(clock.instant())) {
            throw new InvalidSelectionException("EVENT_IN_THE_PAST", "The event must start in the future.");
        }
        Set<String> names = new HashSet<>();
        for (SectionSpec spec : request.sections()) {
            if (!names.add(spec.name().trim().toLowerCase(Locale.ROOT))) {
                throw new InvalidSelectionException("DUPLICATE_SECTION_NAME", "Section names must be unique: " + spec.name());
            }
            if (!request.seatingMode().allows(spec.kind())) {
                throw new InvalidSelectionException("SECTION_KIND_NOT_ALLOWED",
                        "A " + request.seatingMode() + " event cannot have a " + spec.kind() + " section (" + spec.name() + ").");
            }
        }

        EventRecord event = events.save(new EventRecord(organizerId, request.name().trim(), request.venue().trim(),
                request.startsAt(), request.seatingMode(), clock.instant()));

        for (SectionSpec spec : request.sections()) {
            if (spec.kind() == SectionKind.ASSIGNED) {
                createSeatedSection(event, spec);
            } else {
                createStandingSection(event, spec);
            }
        }
        return detail(event);
    }

    @Transactional
    public EventDetail publish(UUID organizerId, UUID eventId) {
        EventRecord event = ownedEvent(organizerId, eventId);
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_NOT_DRAFT", "Only a draft event can be published.");
        }
        event.putOnSale();
        EventDetail published = detail(events.save(event));
        catalogCache.invalidateEvent(eventId);
        return published;
    }

    @Transactional(readOnly = true)
    public PagedResponse<EventSummary> listOnSale(int page, int size) {
        Page<EventRecord> result = events.findByStatusOrderByStartsAtAsc(EventStatus.ON_SALE, PageRequest.of(page, size));
        return new PagedResponse<>(result.getContent().stream().map(EventCatalogService::summary).toList(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public EventDetail getEvent(UUID viewerId, UUID eventId) {
        return detail(visibleEvent(viewerId, eventId));
    }

    public List<SeatView> seatMap(UUID viewerId, UUID eventId, UUID sectionId) {
        EventRecord event = visibleEvent(viewerId, eventId);
        SectionRecord section = catalogCache.sections(event.getId()).stream().filter(x -> x.getId().equals(sectionId)).findFirst()
                .orElseThrow(() -> new NotFoundException("SECTION_NOT_FOUND", "Section not found."));
        if (section.getKind() != SectionKind.ASSIGNED) {
            throw new InvalidSelectionException("NOT_A_SEATED_SECTION", "Standing sections have no seat map.");
        }
        return catalogCache.seats(sectionId).seats();
    }

    private void createSeatedSection(EventRecord event, SectionSpec spec) {
        List<RowSpec> rows = spec.rows();
        if (rows == null || rows.isEmpty() || spec.capacity() != null) {
            throw new InvalidSelectionException("INVALID_SECTION", "Seated section " + spec.name() + " needs rows and no capacity.");
        }
        Set<String> labels = new HashSet<>();
        int total = 0;
        for (RowSpec row : rows) {
            if (!labels.add(row.label().trim().toUpperCase(Locale.ROOT))) {
                throw new InvalidSelectionException("DUPLICATE_ROW", "Row label used twice in " + spec.name() + ": " + row.label());
            }
            total += row.seats();
        }
        if (total > MAX_SEATS_PER_SECTION) {
            throw new InvalidSelectionException("SECTION_TOO_LARGE", "A section can have at most " + MAX_SEATS_PER_SECTION + " seats.");
        }

        SectionRecord section = sections.save(new SectionRecord(event.getId(), spec.name().trim(), SectionKind.ASSIGNED, total,
                spec.priceCents(), spec.currency(), clock.instant()));
        List<SeatRecord> generated = new ArrayList<>(total);
        for (RowSpec row : rows) {
            String label = row.label().trim().toUpperCase(Locale.ROOT);
            for (int number = 1; number <= row.seats(); number++) {
                generated.add(new SeatRecord(event.getId(), section.getId(), label, number));
            }
        }
        seats.saveAll(generated);
    }

    private void createStandingSection(EventRecord event, SectionSpec spec) {
        if (spec.capacity() == null || (spec.rows() != null && !spec.rows().isEmpty())) {
            throw new InvalidSelectionException("INVALID_SECTION", "Standing section " + spec.name() + " needs a capacity and no rows.");
        }
        sections.save(new SectionRecord(event.getId(), spec.name().trim(), SectionKind.GENERAL_ADMISSION, spec.capacity(),
                spec.priceCents(), spec.currency(), clock.instant()));
    }

    private EventRecord ownedEvent(UUID organizerId, UUID eventId) {
        EventRecord event = events.findById(eventId).orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found."));
        if (!event.getOrganizerId().equals(organizerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_EVENT_OWNER", "You can only change your own events.");
        }
        return event;
    }

    // A draft is invisible to everyone but its organizer, and looks exactly like a missing event to them.
    private EventRecord visibleEvent(UUID viewerId, UUID eventId) {
        EventRecord event = events.findById(eventId).orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found."));
        if (event.getStatus() == EventStatus.DRAFT && !event.getOrganizerId().equals(viewerId)) {
            throw new NotFoundException("EVENT_NOT_FOUND", "Event not found.");
        }
        return event;
    }

    private EventDetail detail(EventRecord event) {
        List<SectionView> views = sections.findByEventIdOrderByName(event.getId()).stream().map(section -> {
            long available = section.getKind() == SectionKind.ASSIGNED
                    ? seats.countBySectionIdAndStatus(section.getId(), SeatAllocationStatus.AVAILABLE)
                    : section.standingRoomLeft();
            return new SectionView(section.getId(), section.getName(), section.getKind(), section.getCurrency(),
                    section.getPriceCents(), section.getCapacity(), available);
        }).toList();
        return new EventDetail(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(), event.getSeatingMode(),
                event.getStatus(), views);
    }

    private static EventSummary summary(EventRecord event) {
        return new EventSummary(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(), event.getSeatingMode(), event.getStatus());
    }
}
