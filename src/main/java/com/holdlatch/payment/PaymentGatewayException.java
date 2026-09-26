package com.holdlatch.payment;

/** The payment provider could not be reached or rejected a call we made. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
