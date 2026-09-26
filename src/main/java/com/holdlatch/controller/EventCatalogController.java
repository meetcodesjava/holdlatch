package com.holdlatch.controller;

import com.holdlatch.dto.EventDtos.CreateEventRequest;
import com.holdlatch.dto.EventDtos.EventDetail;
import com.holdlatch.dto.EventDtos.EventSummary;
import com.holdlatch.dto.EventDtos.PagedResponse;
import com.holdlatch.dto.EventDtos.SeatView;
import com.holdlatch.security.Principals;
import com.holdlatch.service.EventCatalogService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventCatalogController {

    private static final int MAX_PAGE_SIZE = 50;

    private final EventCatalogService catalog;

    public EventCatalogController(EventCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateEventRequest request) {
        return catalog.createEvent(Principals.userId(jwt), request);
    }

    @PostMapping("/{eventId}/publish")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventDetail publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return catalog.publish(Principals.userId(jwt), eventId);
    }

    @GetMapping
    public PagedResponse<EventSummary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.listOnSale(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @GetMapping("/{eventId}")
    public EventDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return catalog.getEvent(Principals.userId(jwt), eventId);
    }

    @GetMapping("/{eventId}/sections/{sectionId}/seats")
    public List<SeatView> seatMap(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId, @PathVariable UUID sectionId) {
        return catalog.seatMap(Principals.userId(jwt), eventId, sectionId);
    }
}
