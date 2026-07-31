package com.edutwin.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edutwin.admin.OrganizationDtos;
import com.edutwin.admin.OrganizationService;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.notification.NotificationService;
import com.edutwin.shared.web.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class ProfileNotificationOrganizationIntegrationTest {
    private static final UUID USER_ID = UUID.fromString("70000000-0000-0000-0000-000000000002");
    @Autowired JdbcClient jdbc;
    @Autowired ProfileService profiles;
    @Autowired NotificationService notifications;
    @Autowired OrganizationService organizations;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('STUDENT','Student')").update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, 'profile-notification-test', 'disabled', 'Profile Test', TRUE, 'STUDENT')
                """).param("id", USER_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id,'STUDENT')")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO student_profile(user_id, synthetic, split_name, student_number)
                VALUES (:id,FALSE,'test','20269999')
                """).param("id", USER_ID.toString()).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_notification WHERE recipient_user_id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_organization_membership WHERE user_id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_profile WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM student_profile WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM academic_term WHERE code = '2026-TEST'").update();
        jdbc.sql("DELETE FROM organization_unit WHERE code = 'TEST-COLLEGE'").update();
    }

    @Test
    void profileEditableFieldsOrganizationAndNotificationsRespectOwnershipAndDedupe() {
        EduTwinPrincipal principal = principal();
        ProfileDtos.Profile updated = profiles.update(principal,
                new ProfileDtos.ProfileUpdate("student@example.test", "+86 13800000000", "avatar-3", "green"));
        assertEquals("20269999", updated.officialId());
        assertEquals("student@example.test", updated.email());

        var organization = organizations.create(principal(), new OrganizationDtos.OrganizationWrite(
                "TEST-COLLEGE", "测试学院", "COLLEGE", null, true));
        organizations.replaceUserOrganization(principal, USER_ID, organization.organizationId());
        assertEquals("TEST-COLLEGE", profiles.get(principal).organizations().getFirst().code());

        notifications.create(USER_ID, "TEST", "测试通知", "通知正文", "/profile", "test-dedupe", null);
        notifications.create(USER_ID, "TEST", "测试通知", "通知正文", "/profile", "test-dedupe", null);
        assertEquals(2, notifications.unreadCount(principal).count());
        var page = notifications.list(principal, 0, 20);
        notifications.markRead(principal, page.items().getFirst().notificationId());
        assertEquals(1, notifications.unreadCount(principal).count());

        organizations.createTerm(principal(), new OrganizationDtos.AcademicTermWrite(
                "2026-TEST", "2026 测试学期", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 7, 1), true));
        assertEquals(1, organizations.terms().items().stream()
                .filter(term -> term.code().equals("2026-TEST")).count());
        assertEquals(1, auditCount("ORGANIZATION_CREATED"));
        assertEquals(1, auditCount("USER_ORGANIZATION_REPLACED"));
        assertEquals(1, auditCount("ACADEMIC_TERM_CREATED"));
        assertThrows(DomainException.class, () -> profiles.update(principal,
                new ProfileDtos.ProfileUpdate("bad", null, "avatar-3", "green")));
    }

    private int auditCount(String action) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM admin_audit_event
                WHERE actor_user_id = :id AND action = :action AND outcome = 'SUCCEEDED'
                """).param("id", USER_ID.toString()).param("action", action)
                .query(Integer.class).single();
    }

    private EduTwinPrincipal principal() {
        return new EduTwinPrincipal(USER_ID, "profile-notification-test", "Profile Test", "",
                UserRole.STUDENT, Set.of(USER_ID), true);
    }
}
