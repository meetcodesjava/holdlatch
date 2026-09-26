package com.holdlatch.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A failure the caller caused or can act on; carries the HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Extra machine-readable detail (e.g. which seats were taken), copied into the problem response. */
    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
