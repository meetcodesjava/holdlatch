package com.holdlatch.service;

import com.holdlatch.dto.ReservationDtos.StandingRequest;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.exception.SeatConflictException;
import com.holdlatch.model.domain.SectionKind;
import com.holdlatch.model.persistence.EventRecord;
import com.holdlatch.model.persistence.SectionRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Standing-room sections have no seat identities, so a section with N tickets
 * left is modelled as N numbered slots in AeroKV. Holding q tickets means
 * atomically grabbing q distinct slots; the database counter is the final
 * guard when the tickets are actually sold.
 */
@Component
class GeneralAdmissionStrategy {

    record Selection(SectionRecord section, int quantity) {
        long totalCents() {
            return section.getPriceCents() * quantity;
        }
    }

    List<Selection> plan(EventRecord event, List<SectionRecord> eventSections, List<StandingRequest> requests) {
        Set<UUID> ids = requests.stream().map(StandingRequest::sectionId).collect(Collectors.toSet());
        if (ids.size() != requests.size()) {
            throw new InvalidSelectionException("DUPLICATE_SECTION", "Each standing section may appear only once.");
        }
        Map<UUID, SectionRecord> sections = eventSections.stream().collect(Collectors.toMap(SectionRecord::getId, s -> s));

        List<Selection> selections = new ArrayList<>();
        for (StandingRequest request : requests) {
            SectionRecord section = sections.get(request.sectionId());
            if (section == null) {
                throw new InvalidSelectionException("SECTION_NOT_FOUND", "Section does not belong to this event.")
                        .with("sectionId", request.sectionId());
            }
            if (section.getKind() != SectionKind.GENERAL_ADMISSION || !event.getSeatingMode().allows(section.getKind())) {
                throw new InvalidSelectionException("NOT_A_STANDING_SECTION", "Section " + section.getName() + " is not standing room.");
            }
            if (section.standingRoomLeft() < request.quantity()) {
                throw new SeatConflictException("SECTION_SOLD_OUT", "Not enough standing tickets left in " + section.getName() + ".")
                        .with("sectionId", section.getId()).with("available", section.standingRoomLeft());
            }
            selections.add(new Selection(section, request.quantity()));
        }
        return selections;
    }

    /** One attempt's worth of slot keys: {@code quantity} distinct random slots out of the section's remaining window. */
    List<String> pickSlotKeys(Selection selection) {
        int[] slots = pickDistinct(selection.quantity(), selection.section().standingRoomLeft(), ThreadLocalRandom.current());
        List<String> keys = new ArrayList<>(slots.length);
        for (int slot : slots) {
            keys.add(HoldKeys.standingSlot(selection.section().getId(), slot));
        }
        return keys;
    }

    /** {@code count} distinct integers from [0, bound), uniformly at random. */
    static int[] pickDistinct(int count, int bound, RandomGenerator random) {
        if (count < 0 || count > bound) {
            throw new IllegalArgumentException("cannot pick " + count + " distinct values out of " + bound);
        }
        int[] result = new int[count];
        if (count * 2 >= bound) {
            int[] all = new int[bound];
            for (int i = 0; i < bound; i++) {
                all[i] = i;
            }
            for (int i = 0; i < count; i++) {
                int j = i + random.nextInt(bound - i);
                int tmp = all[i];
                all[i] = all[j];
                all[j] = tmp;
                result[i] = all[i];
            }
            return result;
        }
        Set<Integer> seen = new HashSet<>();
        int filled = 0;
        while (filled < count) {
            int candidate = random.nextInt(bound);
            if (seen.add(candidate)) {
                result[filled++] = candidate;
            }
        }
        return result;
    }
}
