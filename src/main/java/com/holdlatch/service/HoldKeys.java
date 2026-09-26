package com.holdlatch.service;

import java.util.UUID;

/** The AeroKV key naming scheme. Every character used is allowed on AeroKV's wire format. */
final class HoldKeys {

    private HoldKeys() {}

    static String seat(UUID eventId, UUID seatId) {
        return "s:" + eventId + ":" + seatId;
    }

    static String standingSlot(UUID sectionId, int slot) {
        return "g:" + sectionId + ":" + slot;
    }
}
