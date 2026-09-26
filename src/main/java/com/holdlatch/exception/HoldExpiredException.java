package com.holdlatch.exception;

import org.springframework.http.HttpStatus;

/** The seats are no longer held for this buyer; they must pick seats again. */
public class HoldExpiredException extends ApiException {

    public HoldExpiredException() {
        super(HttpStatus.GONE, "HOLD_EXPIRED", "Your hold has expired. Please select your seats again.");
    }
}
