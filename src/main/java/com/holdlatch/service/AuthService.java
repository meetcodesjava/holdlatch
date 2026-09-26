package com.holdlatch.service;

import com.holdlatch.dto.AuthDtos.LoginRequest;
import com.holdlatch.dto.AuthDtos.RegisterRequest;
import com.holdlatch.dto.AuthDtos.TokenResponse;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.EmailAlreadyRegisteredException;
import com.holdlatch.exception.InvalidCredentialsException;
import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.UserRecord;
import com.holdlatch.repository.PersistentUserRepository;
import com.holdlatch.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    // BCrypt only looks at the first 72 bytes; refuse anything longer instead of silently truncating it.
    private static final int BCRYPT_MAX_BYTES = 72;

    private final PersistentUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;
    // Compared against when the email is unknown, so a missing account costs the same time as a wrong password.
    private final String timingDummyHash;

    public AuthService(PersistentUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode("timing-equalizer-" + UUID.randomUUID());
    }

    @Transactional
    public UserRecord register(RegisterRequest request) {
        if (request.role() == UserRole.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ALLOWED", "Accounts can only register as CUSTOMER or ORGANIZER.");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must be at most 72 bytes.");
        }
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new EmailAlreadyRegisteredException();
        }
        UserRecord user = new UserRecord(request.email(), passwordEncoder.encode(request.password()),
                request.displayName().trim(), request.role(), clock.instant());
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous registrations for the same email: the database's unique index decides the loser.
            throw new EmailAlreadyRegisteredException();
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        UserRecord user = users.findByEmailIgnoreCase(request.email().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : timingDummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        JwtService.IssuedToken token = jwtService.issueFor(user);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    @Transactional(readOnly = true)
    public UserRecord getUser(UUID id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found."));
    }
}
