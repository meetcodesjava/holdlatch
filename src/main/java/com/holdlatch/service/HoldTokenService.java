package com.holdlatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.config.HoldProperties;
import com.holdlatch.exception.InvalidSelectionException;
import com.holdlatch.model.domain.ReservationHoldToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Signs and verifies hold tokens (base64url(payload) + "." + base64url(HMAC-SHA256)). */
@Service
public class HoldTokenService {

    private static final String HMAC = "HmacSHA256";
    private static final int MAX_TOKEN_CHARS = 16_384;

    private final ObjectMapper objectMapper;
    private final SecretKeySpec key;

    public HoldTokenService(HoldProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.key = new SecretKeySpec(props.tokenSecret().getBytes(StandardCharsets.UTF_8), HMAC);
    }

    public String issue(ReservationHoldToken token) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(token));
            return payload + "." + sign(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize hold token", e);
        }
    }

    /** Checks the signature and returns the payload. Expiry is the caller's decision (settlement has a grace period). */
    public ReservationHoldToken parse(String tokenString) {
        if (tokenString == null || tokenString.length() > MAX_TOKEN_CHARS) {
            throw invalid();
        }
        int dot = tokenString.indexOf('.');
        if (dot <= 0 || dot != tokenString.lastIndexOf('.')) {
            throw invalid();
        }
        String payload = tokenString.substring(0, dot);
        byte[] given = tokenString.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = sign(payload).getBytes(StandardCharsets.US_ASCII);
        // Constant-time compare: an early-exit equals() would leak how many leading characters matched.
        if (!MessageDigest.isEqual(given, expected)) {
            throw invalid();
        }
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(payload), ReservationHoldToken.class);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static InvalidSelectionException invalid() {
        return new InvalidSelectionException("INVALID_HOLD_TOKEN", "The hold token is not valid.");
    }
}
