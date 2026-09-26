package com.holdlatch.security;

import com.holdlatch.config.SecurityProperties;
import com.holdlatch.model.persistence.UserRecord;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues signed (HS256) access tokens and builds the matching decoder that Spring Security uses to verify them. */
@Service
public class JwtService {

    public record IssuedToken(String value, long expiresInSeconds) {}

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecurityProperties props;
    private final Clock clock;

    public JwtService(SecurityProperties props, Clock clock) {
        SecretKey key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        this.decoder = nimbus;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issueFor(UserRecord user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("role", user.getRole().name())
                .claim("email", user.getEmail())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, props.accessTokenTtl().toSeconds());
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
