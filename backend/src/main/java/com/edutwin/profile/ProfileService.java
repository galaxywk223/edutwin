package com.edutwin.profile;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private static final Set<String> AVATARS = Set.of(
            "avatar-1", "avatar-2", "avatar-3", "avatar-4",
            "avatar-5", "avatar-6", "avatar-7", "avatar-8");
    private static final Set<String> COLORS = Set.of(
            "teal", "blue", "green", "amber", "red", "gray", "violet", "rose");
    private final JdbcClient jdbc;

    public ProfileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProfileDtos.Profile get(EduTwinPrincipal principal) {
        var organizations = jdbc.sql("""
                SELECT o.id, o.code, o.display_name, o.unit_type
                FROM user_organization_membership m
                JOIN organization_unit o ON o.id = m.organization_id
                WHERE m.user_id = :id AND m.ended_at IS NULL
                ORDER BY m.membership_type, o.code
                """).param("id", principal.userId().toString())
                .query((rs, row) -> new ProfileDtos.OrganizationRef(
                        java.util.UUID.fromString(rs.getString("id")), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("unit_type"))).list();
        return jdbc.sql("""
                SELECT u.id, u.username, u.display_name,
                       COALESCE(sp.student_number, tp.staff_number) official_id,
                       p.email, p.phone, p.avatar_key, p.theme_color
                FROM user_account u
                LEFT JOIN user_profile p ON p.user_id = u.id
                LEFT JOIN student_profile sp ON sp.user_id = u.id
                LEFT JOIN teacher_profile tp ON tp.user_id = u.id
                WHERE u.id = :id
                """).param("id", principal.userId().toString())
                .query((rs, row) -> new ProfileDtos.Profile(
                        java.util.UUID.fromString(rs.getString("id")), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("official_id"),
                        principal.availableRoles().stream().map(role -> role.getValue()).sorted().toList(),
                        organizations, rs.getString("email"), rs.getString("phone"),
                        rs.getString("avatar_key") == null ? "avatar-1" : rs.getString("avatar_key"),
                        rs.getString("theme_color") == null ? "teal" : rs.getString("theme_color")))
                .optional().orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND,
                        "PROFILE_NOT_FOUND", "The profile does not exist."));
    }

    @Transactional
    public ProfileDtos.Profile update(EduTwinPrincipal principal, ProfileDtos.ProfileUpdate request) {
        if (request == null) throw invalid("A profile update is required.");
        String email = optional(request.email(), 254);
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw invalid("The email address is invalid.");
        String phone = optional(request.phone(), 30);
        if (phone != null && !phone.matches("^[+0-9() -]{6,30}$"))
            throw invalid("The phone number is invalid.");
        String avatar = request.avatarKey() == null ? get(principal).avatarKey() : request.avatarKey().trim();
        String color = request.themeColor() == null ? get(principal).themeColor() : request.themeColor().trim();
        if (!AVATARS.contains(avatar) || !COLORS.contains(color))
            throw invalid("The avatar or theme color is not an allowed preset.");
        jdbc.sql("""
                INSERT INTO user_profile(user_id, email, phone, avatar_key, theme_color)
                VALUES (:id, :email, :phone, :avatar, :color)
                ON DUPLICATE KEY UPDATE email = VALUES(email), phone = VALUES(phone),
                    avatar_key = VALUES(avatar_key), theme_color = VALUES(theme_color)
                """).param("email", email).param("phone", phone).param("avatar", avatar)
                .param("color", color).param("id", principal.userId().toString()).update();
        return get(principal);
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid("A profile field exceeds its maximum length.");
        return normalized;
    }

    private static DomainException invalid(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, "PROFILE_INVALID", detail);
    }
}
