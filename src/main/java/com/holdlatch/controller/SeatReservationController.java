package com.holdlatch.controller;

import com.holdlatch.dto.ReservationDtos.HoldRequest;
import com.holdlatch.dto.ReservationDtos.HoldResponse;
import com.holdlatch.dto.ReservationDtos.ReleaseRequest;
import com.holdlatch.dto.ReservationDtos.StandingRequest;
import com.holdlatch.model.domain.ReservationHoldToken;
import com.holdlatch.security.Principals;
import com.holdlatch.service.ReservationCoordinatorService;
import com.holdlatch.service.ReservationCoordinatorService.IssuedHold;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatReservationController {

    private final ReservationCoordinatorService coordinator;

    public SeatReservationController(ReservationCoordinatorService coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/api/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId, @Valid @RequestBody HoldRequest request) {
        IssuedHold issued = coordinator.hold(Principals.userId(jwt), eventId, request);
        ReservationHoldToken token = issued.token();
        return new HoldResponse(issued.tokenString(), token.holdId(), token.expiresAt(), token.amountCents(), token.currency(),
                token.seatIds(), token.standing().stream().map(l -> new StandingRequest(l.sectionId(), l.quantity())).toList());
    }

    @PostMapping("/api/holds/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReleaseRequest request) {
        coordinator.release(Principals.userId(jwt), request.holdToken());
    }
}
