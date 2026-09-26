package com.holdlatch.exception;

import org.springframework.http.HttpStatus;

/** The request was valid but the seats/tickets are not available right now (taken, sold out, or contended). */
public class SeatConflictException extends ApiException {

    public SeatConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
