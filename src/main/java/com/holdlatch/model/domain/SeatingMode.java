package com.holdlatch.model.domain;

/** How an organizer sells an event: numbered seats, standing room, or both. */
public enum SeatingMode {
    ASSIGNED, GENERAL_ADMISSION, HYBRID;

    public boolean allows(SectionKind kind) {
        return switch (this) {
            case ASSIGNED -> kind == SectionKind.ASSIGNED;
            case GENERAL_ADMISSION -> kind == SectionKind.GENERAL_ADMISSION;
            case HYBRID -> true;
        };
    }
}
