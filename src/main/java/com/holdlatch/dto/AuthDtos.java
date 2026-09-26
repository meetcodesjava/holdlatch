package com.holdlatch.dto;

import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.UserRecord;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull UserRole role) {}

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}

    public record UserResponse(UUID id, String email, String displayName, UserRole role) {
        public static UserResponse from(UserRecord user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
        }
    }
}
