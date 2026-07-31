package com.edutwin.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.edutwin.api.model.UserRole;
import com.edutwin.dashboard.TeacherDashboardService;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class UserProvisioningImportIntegrationTest {
    private static final String USERNAME = "bulk-import-student";
    private static final UUID ADMIN_ID = UUID.fromString("70000000-0000-0000-0000-000000000099");
    private static final UUID SECOND_ADMIN_ID = UUID.fromString("70000000-0000-0000-0000-000000000098");
    private static final UUID COURSE_ID = UUID.fromString("70000000-0000-0000-0000-000000000098");

    @Autowired private UserProvisioningImportService service;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private TeacherDashboardService teacherDashboardService;

    @BeforeEach
    void seedStudentRole() {
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", SECOND_ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", SECOND_ADMIN_ID.toString()).update();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('STUDENT', 'Student'), ('ADMIN', 'Admin')")
                .update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, 'bulk-import-admin', 'disabled', 'Bulk Import Admin', TRUE, 'ADMIN')
                """).param("id", ADMIN_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'ADMIN')")
                .param("id", ADMIN_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, 'bulk-import-admin-second', 'disabled', 'Second Bulk Import Admin', TRUE, 'ADMIN')
                """).param("id", SECOND_ADMIN_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'ADMIN')")
                .param("id", SECOND_ADMIN_ID.toString()).update();
        clearInvocations(teacherDashboardService);
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM course_enrollment WHERE student_id IN (SELECT id FROM user_account WHERE username = :username)")
                .param("username", USERNAME).update();
        jdbc.sql("DELETE FROM student_profile WHERE user_id IN (SELECT id FROM user_account WHERE username = :username)")
                .param("username", USERNAME).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id IN (SELECT id FROM user_account WHERE username = :username)")
                .param("username", USERNAME).update();
        jdbc.sql("DELETE FROM user_account WHERE username = :username").param("username", USERNAME).update();
        jdbc.sql("DELETE FROM teaching_assignment WHERE course_id = :id").param("id", COURSE_ID.toString()).update();
        jdbc.sql("DELETE FROM course WHERE id = :id").param("id", COURSE_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", SECOND_ADMIN_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", SECOND_ADMIN_ID.toString()).update();
    }

    @Test
    void csvPreviewIsReadOnlyAndCommitReturnsTheOnlyPlaintextCredential() {
        MockMultipartFile file = usersCsv("批量学生", true);
        AdminDtos.UserImportPreview preview = service.preview(List.of(file));
        assertTrue(preview.valid());
        assertEquals(1, preview.createCount());
        assertEquals(0, countUser());

        AdminDtos.UserImportResult result = service.commit(actor(), List.of(file), preview.fileSha256());
        assertEquals(1, result.createdCount());
        assertEquals(1, result.credentials().size());
        assertEquals("BULK_IMPORT", jdbc.sql("SELECT origin_type FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(String.class).single());
        String persistedHash = jdbc.sql("SELECT password_hash FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(String.class).single();
        assertFalse(persistedHash.contains(result.credentials().getFirst().temporaryPassword()));

        MockMultipartFile update = usersCsv("批量学生更新", false);
        AdminDtos.UserImportPreview updatePreview = service.preview(List.of(update));
        assertTrue(updatePreview.valid());
        assertEquals(1, updatePreview.updateCount());
        AdminDtos.UserImportResult updated = service.commit(actor(), List.of(update), updatePreview.fileSha256());
        assertTrue(updated.credentials().isEmpty());
        assertEquals("批量学生更新", jdbc.sql("SELECT display_name FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(String.class).single());
    }

    @Test
    void rejectsBatchWhoseFinalProjectionDisablesEveryAdministrator() {
        MockMultipartFile file = new MockMultipartFile("files", "users.csv", "text/csv",
                ("\ufeffusername,display_name,role,enabled,student_number,staff_number,college,department,major,cohort_year,class_name,academic_title\r\n"
                        + "bulk-import-admin,Bulk Import Admin,ADMIN,false,,,,,,,,\r\n"
                        + "bulk-import-admin-second,Second Bulk Import Admin,ADMIN,false,,,,,,,,\r\n")
                        .getBytes(StandardCharsets.UTF_8));

        AdminDtos.UserImportPreview preview = service.preview(List.of(file));
        assertFalse(preview.valid());
        assertTrue(preview.errors().stream().anyMatch(error -> error.code().equals("LAST_ADMIN_REQUIRED")));
        DomainException error = assertThrows(DomainException.class,
                () -> service.commit(actor(), List.of(file), preview.fileSha256()));
        assertEquals("USER_IMPORT_INVALID", error.code());
        assertEquals(2, jdbc.sql("""
                SELECT COUNT(*) FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                """).query(Integer.class).single());
    }

    @Test
    void membershipImportRevokesTokensAndEvictsDashboardOnlyWhenMembershipChanges() {
        jdbc.sql("""
                INSERT INTO course(id, code, title, starts_on, data_version, status, credits)
                VALUES (:id, 'IMPORT-101', 'Import membership course', '2026-09-01',
                        'import-test', 'PUBLISHED', 2.0)
                """).param("id", COURSE_ID.toString()).update();
        MockMultipartFile users = usersCsv("批量学生", true);
        MockMultipartFile memberships = membershipsCsv();
        AdminDtos.UserImportPreview preview = service.preview(List.of(users, memberships));

        service.commit(actor(), List.of(users, memberships), preview.fileSha256());

        assertEquals(1L, tokenVersion());
        verify(teacherDashboardService).evict(COURSE_ID);

        clearInvocations(teacherDashboardService);
        AdminDtos.UserImportPreview repeatedPreview = service.preview(List.of(users, memberships));
        service.commit(actor(), List.of(users, memberships), repeatedPreview.fileSha256());

        assertEquals(2L, tokenVersion());
        verify(teacherDashboardService, never()).evict(COURSE_ID);
    }

    private MockMultipartFile usersCsv(String displayName, boolean enabled) {
        String csv = "\ufeffusername,display_name,role,enabled,student_number,staff_number,college,department,major,cohort_year,class_name,academic_title\r\n"
                + USERNAME + "," + displayName + ",STUDENT," + enabled
                + ",20260001,,计算机与数据科学学院,,计算机科学与技术,2026,计科 1 班,\r\n";
        return new MockMultipartFile("files", "users.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile membershipsCsv() {
        String csv = "\ufeffusername,course_code,membership_type,membership_status\r\n"
                + USERNAME + ",IMPORT-101,LEARNER,ACTIVE\r\n";
        return new MockMultipartFile("files", "course_memberships.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private long tokenVersion() {
        return jdbc.sql("SELECT token_version FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(Long.class).single();
    }

    private int countUser() {
        return jdbc.sql("SELECT COUNT(*) FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(Integer.class).single();
    }

    private EduTwinPrincipal actor() {
        return new EduTwinPrincipal(ADMIN_ID, "test-admin", "Test Admin", "",
                UserRole.ADMIN, Set.of(), true);
    }
}
