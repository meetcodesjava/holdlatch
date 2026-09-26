package com.holdlatch.exception;

import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists.");
    }
}
