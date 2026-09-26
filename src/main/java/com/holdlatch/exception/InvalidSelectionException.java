package com.holdlatch.exception;

import org.springframework.http.HttpStatus;

/** The request itself is wrong (unknown seat, too many seats, mixed currencies...), as opposed to merely unlucky. */
public class InvalidSelectionException extends ApiException {

    public InvalidSelectionException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
