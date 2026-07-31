package com.edutwin.identity;

import com.edutwin.api.model.UserRole;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record EduTwinPrincipal(
        UUID userId,
        String username,
        String displayName,
        String passwordHash,
        UserRole role,
        Set<UserRole> availableRoles,
        Set<UUID> accessibleCourseIds,
        boolean enabled,
        boolean mustChangePassword,
        long tokenVersion) implements UserDetails {

    public EduTwinPrincipal(
            UUID userId, String username, String displayName, String passwordHash,
            UserRole role, Set<UUID> accessibleCourseIds, boolean enabled) {
        this(userId, username, displayName, passwordHash, role, Set.of(role),
                accessibleCourseIds, enabled, false, 0);
    }

    public EduTwinPrincipal(
            UUID userId, String username, String displayName, String passwordHash,
            UserRole role, Set<UUID> accessibleCourseIds, boolean enabled, boolean mustChangePassword) {
        this(userId, username, displayName, passwordHash, role, Set.of(role),
                accessibleCourseIds, enabled, mustChangePassword, 0);
    }

    public EduTwinPrincipal {
        availableRoles = Set.copyOf(availableRoles);
        accessibleCourseIds = Set.copyOf(accessibleCourseIds);
        if (!availableRoles.contains(role)) {
            throw new IllegalArgumentException("The active role must be included in available roles.");
        }
    }

    public EduTwinPrincipal withoutPassword() {
        return new EduTwinPrincipal(
                userId, username, displayName, "", role, availableRoles, accessibleCourseIds,
                enabled, mustChangePassword, tokenVersion);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Set.of(new SimpleGrantedAuthority("ROLE_" + role.getValue()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
