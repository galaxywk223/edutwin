package com.edutwin.identity;

import com.edutwin.shared.config.SecurityProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String ISSUER = "edutwin";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    public JwtService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(EduTwinPrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(principal.userId().toString())
                .claim("username", principal.username())
                .claim("displayName", principal.displayName())
                .claim("role", principal.role().getValue())
                .claim("availableRoles", principal.availableRoles().stream()
                        .map(com.edutwin.api.model.UserRole::getValue).sorted().toList())
                .claim("tokenVersion", principal.tokenVersion())
                .claim("mustChangePassword", principal.mustChangePassword())
                .claim(
                        "courses",
                        principal.accessibleCourseIds().stream().map(Object::toString).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    public record IssuedToken(String value, OffsetDateTime expiresAt) {}
}
