package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.dto.ReservationDtos.HoldRequest;
import com.holdlatch.engine.aerokv.AeroKvClient;
import com.holdlatch.engine.aerokv.AeroKvClient.HoldOutcome;
import com.holdlatch.engine.aerokv.AeroKvUnavailableException;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.SeatConflictException;
import com.holdlatch.model.domain.ReservationHoldToken;
import com.holdlatch.model.domain.ReservationHoldToken.StandingLine;
import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.persistence.SeatRecord;
import com.holdlatch.repository.PersistentSeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The hold flow: validate against the database, take the holds in AeroKV,
 * then hand back a signed token. Deliberately not transactional - no database
 * connection is held open while talking to AeroKV.
 */
@Service
public class ReservationCoordinatorService {

    public record IssuedHold(String tokenString, ReservationHoldToken token) {}

    private static final Logger log = LoggerFactory.getLogger(ReservationCoordinatorService.class);

    private final SeatAllocationService allocation;
    private final GeneralAdmissionStrategy standingStrategy;
    private final AeroKvClient aeroKv;
    private final HoldTokenService tokens;
    private final PersistentSeatRepository seats;
    private final HoldProperties props;
    private final Clock clock;

    ReservationCoordinatorService(SeatAllocationService allocation, GeneralAdmissionStrategy standingStrategy, AeroKvClient aeroKv,
                                  HoldTokenService tokens, PersistentSeatRepository seats, HoldProperties props, Clock clock) {
        this.allocation = allocation;
        this.standingStrategy = standingStrategy;
        this.aeroKv = aeroKv;
        this.tokens = tokens;
        this.seats = seats;
        this.props = props;
        this.clock = clock;
    }

    public IssuedHold hold(UUID userId, UUID eventId, HoldRequest request) {
        SeatAllocationService.Plan plan = allocation.plan(eventId, request);

        // Taken before any AeroKV call, so the token can never claim to outlive the real hold.
        Instant issuedAt = clock.instant();
        String holdId = UUID.randomUUID().toString();
        List<String> attempted = new ArrayList<>();
        List<String> held = new ArrayList<>();
        List<UUID> seatIds = new ArrayList<>();

        try {
            if (plan.seats() != null) {
                List<SeatRecord> chosen = plan.seats().seats();
                chosen.forEach(s -> seatIds.add(s.getId()));
                List<String> keys = chosen.stream().map(s -> HoldKeys.seat(eventId, s.getId())).toList();
                attempted.addAll(keys);
                HoldOutcome outcome = aeroKv.multiHold(keys, holdId, props.ttl());
                if (outcome != HoldOutcome.ACQUIRED) {
                    throw failure(outcome, "One or more of those seats were just taken by someone else.").with("seatIds", seatIds);
                }
                held.addAll(keys);
                confirmSeatsStillFree(eventId, seatIds);
            }
            for (GeneralAdmissionStrategy.Selection selection : plan.standing()) {
                held.addAll(acquireStandingRoom(selection, holdId, attempted));
            }
        } catch (RuntimeException e) {
            // All-or-nothing: whatever was grabbed before the failure is given back at once.
            releaseQuietly(attempted, holdId);
            throw e;
        }

        List<StandingLine> lines = plan.standing().stream()
                .map(s -> new StandingLine(s.section().getId(), s.quantity(), s.section().getPriceCents()))
                .toList();
        ReservationHoldToken token = new ReservationHoldToken(holdId, userId, eventId, issuedAt.plus(props.ttl()),
                plan.totalCents(), plan.currency(), List.copyOf(held), List.copyOf(seatIds), lines);
        log.debug("Hold {} issued for user {} on event {} ({} keys)", holdId, userId, eventId, held.size());
        return new IssuedHold(tokens.issue(token), token);
    }

    /** Gives back a hold the user no longer wants. Safe to repeat, and never touches a seat someone else now holds. */
    public void release(UUID userId, String tokenString) {
        ReservationHoldToken token = tokens.parse(tokenString);
        if (!token.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_HOLD_OWNER", "That hold belongs to someone else.");
        }
        for (String key : token.keys()) {
            aeroKv.releaseIfOwner(key, token.holdId());
        }
    }

    // A booking can complete between our database check and our hold; if so the seat is gone for good.
    private void confirmSeatsStillFree(UUID eventId, List<UUID> seatIds) {
        List<UUID> unavailable = seats.findByEventIdAndIdIn(eventId, seatIds).stream()
                .filter(s -> s.getStatus() != SeatAllocationStatus.AVAILABLE)
                .map(SeatRecord::getId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatConflictException("SEAT_UNAVAILABLE", "Some of the seats are already booked.").with("seatIds", unavailable);
        }
    }

    private List<String> acquireStandingRoom(GeneralAdmissionStrategy.Selection selection, String holdId, List<String> attempted) {
        for (int attempt = 1; attempt <= props.standingRoomAttempts(); attempt++) {
            List<String> keys = standingStrategy.pickSlotKeys(selection);
            attempted.addAll(keys);
            HoldOutcome outcome = aeroKv.multiHold(keys, holdId, props.ttl());
            if (outcome == HoldOutcome.ACQUIRED) {
                return keys;
            }
            if (outcome == HoldOutcome.CAPACITY_EXCEEDED) {
                throw failure(outcome, "");
            }
            // CONFLICT: another buyer grabbed one of our random slots; nothing was taken, so just pick again.
        }
        throw new SeatConflictException("SECTION_BUSY", "That section is very busy right now. Please try again.")
                .with("sectionId", selection.section().getId());
    }

    private static ApiException failure(HoldOutcome outcome, String conflictMessage) {
        if (outcome == HoldOutcome.CAPACITY_EXCEEDED) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_ENGINE_FULL",
                    "The reservation engine is at capacity. Please retry shortly.");
        }
        return new SeatConflictException("SEAT_UNAVAILABLE", conflictMessage);
    }

    private void releaseQuietly(List<String> keys, String holdId) {
        for (String key : keys) {
            try {
                aeroKv.releaseIfOwner(key, holdId);
            } catch (AeroKvUnavailableException e) {
                log.warn("Could not release {} after a failed hold; it will expire on its own", key);
            }
        }
    }
}
