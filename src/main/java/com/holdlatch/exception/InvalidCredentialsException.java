package com.holdlatch.exception;

import org.springframework.http.HttpStatus;

/** Deliberately identical for "no such user" and "wrong password" so login cannot be used to discover which emails exist. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
    }
}
