package com.edutwin.identity;

import com.edutwin.admin.BusinessAuditService;
import com.edutwin.api.model.AuthSession;
import com.edutwin.api.model.AuthenticatedUser;
import com.edutwin.api.model.LoginRequest;
import com.edutwin.shared.web.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final AccountRepository accountRepository;
    private final BusinessAuditService businessAudit;

    public AuthenticationService(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            AccountRepository accountRepository,
            BusinessAuditService businessAudit) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.accountRepository = accountRepository;
        this.businessAudit = businessAudit;
    }

    public AuthSession login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.getUsername(), request.getPassword()));
        EduTwinPrincipal principal = (EduTwinPrincipal) authentication.getPrincipal();
        jdbcClient.sql("UPDATE user_account SET last_active_role = :role WHERE id = :id")
                .param("role", principal.role().getValue())
                .param("id", principal.userId().toString()).update();
        JwtService.IssuedToken token = jwtService.issue(principal);
        return new AuthSession(
                token.value(),
                AuthSession.TokenTypeEnum.BEARER,
                token.expiresAt(),
                toAuthenticatedUser(principal));
    }

    public static AuthenticatedUser toAuthenticatedUser(EduTwinPrincipal principal) {
        return new AuthenticatedUser(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.role(),
                principal.role(),
                principal.availableRoles().stream().sorted(java.util.Comparator.comparing(
                                com.edutwin.api.model.UserRole::getValue))
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                principal.mustChangePassword(),
                principal.accessibleCourseIds());
    }

    @Transactional
    public void changePassword(
            EduTwinPrincipal principal, String currentPassword, String newPassword) {
        try {
            if (newPassword == null || newPassword.length() < 12) {
                throw new DomainException(
                        HttpStatus.BAD_REQUEST,
                        "PASSWORD_TOO_SHORT",
                        "The new password must contain at least 12 characters.");
            }
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    principal.username(), currentPassword));
            jdbcClient.sql("""
                            UPDATE user_account
                            SET password_hash = :password, must_change_password = FALSE,
                                token_version = token_version + 1
                            WHERE id = :id
                            """)
                    .param("password", passwordEncoder.encode(newPassword))
                    .param("id", principal.userId().toString())
                    .update();
            businessAudit.record(principal, "PASSWORD_CHANGED", "USER", principal.userId().toString(),
                    "SUCCEEDED", null, null,
                    Map.of("mustChangePassword", principal.mustChangePassword()),
                    Map.of("mustChangePassword", false), null);
        } catch (RuntimeException exception) {
            businessAudit.record(principal, "PASSWORD_CHANGED", "USER", principal.userId().toString(),
                    "FAILED", exception.getMessage(), errorCode(exception, "AUTH_PASSWORD_CURRENT_INVALID"),
                    null, null, null);
            throw exception;
        }
    }

    @Transactional
    public RoleSession switchRole(EduTwinPrincipal principal, String requestedRole) {
        try {
            com.edutwin.api.model.UserRole target;
            try {
                target = com.edutwin.api.model.UserRole.fromValue(
                        requestedRole == null ? "" : requestedRole.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "AUTH_ROLE_INVALID",
                        "The requested role is invalid.");
            }
            if (!principal.availableRoles().contains(target)) {
                throw new DomainException(HttpStatus.FORBIDDEN, "AUTH_ROLE_NOT_GRANTED",
                        "The requested role is not granted to this account.");
            }
            int updated = jdbcClient.sql("""
                    UPDATE user_account
                    SET last_active_role = :role, token_version = token_version + 1
                    WHERE id = :id AND enabled = TRUE
                    """).param("role", target.getValue())
                    .param("id", principal.userId().toString()).update();
            if (updated != 1) {
                throw new DomainException(HttpStatus.UNAUTHORIZED,
                        "AUTH_ACCOUNT_UNAVAILABLE", "The account is no longer available.");
            }
            EduTwinPrincipal refreshed = accountRepository.findByUserId(principal.userId())
                    .orElseThrow(() -> new DomainException(HttpStatus.UNAUTHORIZED,
                            "AUTH_ACCOUNT_UNAVAILABLE", "The account is no longer available."));
            JwtService.IssuedToken token = jwtService.issue(refreshed);
            businessAudit.record(principal, "ACTIVE_ROLE_CHANGED", "USER", principal.userId().toString(),
                    "SUCCEEDED", null, null,
                    Map.of("activeRole", principal.role().getValue()),
                    Map.of("activeRole", refreshed.role().getValue()), null);
            return new RoleSession(token.value(), "Bearer", token.expiresAt(), toUserView(refreshed));
        } catch (RuntimeException exception) {
            businessAudit.record(principal, "ACTIVE_ROLE_CHANGED", "USER", principal.userId().toString(),
                    "FAILED", exception.getMessage(), errorCode(exception, "AUTH_ROLE_CHANGE_FAILED"),
                    Map.of("activeRole", principal.role().getValue()), null, null);
            throw exception;
        }
    }

    private static String errorCode(RuntimeException exception, String fallback) {
        return exception instanceof DomainException domain ? domain.code() : fallback;
    }

    public static AuthenticatedUserView toUserView(EduTwinPrincipal principal) {
        return new AuthenticatedUserView(
                principal.userId(), principal.username(), principal.displayName(),
                principal.role().getValue(), principal.role().getValue(),
                principal.availableRoles().stream().map(com.edutwin.api.model.UserRole::getValue)
                        .sorted().toList(),
                principal.mustChangePassword(), principal.accessibleCourseIds());
    }

    public record RoleSwitchRequest(String role) {}
    public record RoleSession(String accessToken, String tokenType,
            java.time.OffsetDateTime expiresAt, AuthenticatedUserView user) {}
    public record AuthenticatedUserView(UUID userId, String username, String displayName,
            String role, String activeRole, List<String> availableRoles,
            boolean mustChangePassword, java.util.Set<UUID> accessibleCourseIds) {}
}
