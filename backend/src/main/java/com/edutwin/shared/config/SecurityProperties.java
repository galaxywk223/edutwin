package com.edutwin.shared.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("edutwin.security")
public record SecurityProperties(String jwtSecret, Duration tokenTtl) {

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < 32) {
            throw new IllegalArgumentException("edutwin.security.jwt-secret must contain at least 32 bytes");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("edutwin.security.token-ttl must be positive");
        }
    }
}
