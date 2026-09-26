package com.holdlatch.engine.aerokv;

/** AeroKV could not be reached, timed out, rejected our credentials, or sent an unexpected reply. */
public class AeroKvUnavailableException extends RuntimeException {

    public AeroKvUnavailableException(String message) {
        super(message);
    }

    public AeroKvUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
