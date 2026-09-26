package com.holdlatch.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class Principals {

    private Principals() {}

    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
