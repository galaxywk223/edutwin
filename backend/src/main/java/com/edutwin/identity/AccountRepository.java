package com.edutwin.identity;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    Optional<EduTwinPrincipal> findByUsername(String username);

    Optional<EduTwinPrincipal> findByUserId(UUID userId);

    boolean isTokenCurrent(UUID userId, long tokenVersion, String activeRole);
}
