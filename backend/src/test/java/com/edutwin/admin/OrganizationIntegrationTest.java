package com.edutwin.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class OrganizationIntegrationTest {
    private static final UUID ACTOR_ID = UUID.fromString("70000000-0000-0000-0000-000000000088");
    @Autowired JdbcClient jdbc;
    @Autowired OrganizationService organizations;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('ADMIN','Administrator')").update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, 'organization-admin-test', 'disabled', 'Organization Admin', TRUE, 'ADMIN')
                """).param("id", ACTOR_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id,'ADMIN')")
                .param("id", ACTOR_ID.toString()).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("UPDATE organization_unit SET parent_id = NULL WHERE code LIKE 'CYCLE-TEST-%'").update();
        jdbc.sql("DELETE FROM organization_unit WHERE code LIKE 'CYCLE-TEST-%'").update();
        jdbc.sql("DELETE FROM academic_term WHERE code LIKE 'CYCLE-TEST-%'").update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", ACTOR_ID.toString()).update();
    }

    @Test
    void rejectsTwoNodeParentCycle() {
        var root = create("CYCLE-TEST-ROOT", "Root", "COLLEGE", null);
        var child = create("CYCLE-TEST-CHILD", "Child", "DEPARTMENT", root.organizationId());

        DomainException error = assertThrows(DomainException.class, () -> organizations.update(actor(),
                root.organizationId(), write("CYCLE-TEST-ROOT", "Root", "COLLEGE", child.organizationId())));

        assertEquals("ORGANIZATION_PARENT_INVALID", error.code());
        assertNull(parentId(root.organizationId()));
        assertEquals(1, auditCount("ORGANIZATION_UPDATED", "FAILED"));
    }

    @Test
    void rejectsMultiLevelParentCycle() {
        var root = create("CYCLE-TEST-ROOT", "Root", "COLLEGE", null);
        var department = create("CYCLE-TEST-DEPARTMENT", "Department", "DEPARTMENT", root.organizationId());
        var major = create("CYCLE-TEST-MAJOR", "Major", "MAJOR", department.organizationId());
        var classUnit = create("CYCLE-TEST-CLASS", "Class", "CLASS", major.organizationId());

        DomainException error = assertThrows(DomainException.class, () -> organizations.update(actor(),
                root.organizationId(), write("CYCLE-TEST-ROOT", "Root", "COLLEGE", classUnit.organizationId())));

        assertEquals("ORGANIZATION_PARENT_INVALID", error.code());
        assertNull(parentId(root.organizationId()));
    }

    @Test
    void movesOrganizationBetweenIndependentBranches() {
        var root = create("CYCLE-TEST-ROOT", "Root", "COLLEGE", null);
        var source = create("CYCLE-TEST-SOURCE", "Source", "DEPARTMENT", root.organizationId());
        var target = create("CYCLE-TEST-TARGET", "Target", "DEPARTMENT", root.organizationId());
        var major = create("CYCLE-TEST-MAJOR", "Major", "MAJOR", source.organizationId());

        var moved = organizations.update(actor(), major.organizationId(),
                write("CYCLE-TEST-MAJOR", "Major", "MAJOR", target.organizationId()));

        assertEquals(target.organizationId(), moved.parentId());
        assertEquals(target.organizationId(), parentId(major.organizationId()));
        assertEquals(1, auditCount("ORGANIZATION_UPDATED", "SUCCEEDED"));
    }

    @Test
    void serializesConcurrentParentMovesAndRejectsTheResultingCycle() throws Exception {
        var left = create("CYCLE-TEST-LEFT", "Left", "COLLEGE", null);
        var right = create("CYCLE-TEST-RIGHT", "Right", "COLLEGE", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var leftMove = executor.submit(() -> moveAfterSignal(
                    ready, start, left, right.organizationId()));
            var rightMove = executor.submit(() -> moveAfterSignal(
                    ready, start, right, left.organizationId()));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successes = (leftMove.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (rightMove.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }

        assertFalse(left.organizationId().equals(parentId(right.organizationId()))
                && right.organizationId().equals(parentId(left.organizationId())));
    }

    @Test
    void auditsRejectedAcademicTermAndUserOrganizationMutations() {
        DomainException termError = assertThrows(DomainException.class,
                () -> organizations.createTerm(actor(), new OrganizationDtos.AcademicTermWrite(
                        "CYCLE-TEST-TERM", "Invalid term", LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 8, 1), true)));
        assertEquals("ACADEMIC_TERM_INVALID", termError.code());

        var disabled = organizations.create(actor(), new OrganizationDtos.OrganizationWrite(
                "CYCLE-TEST-DISABLED", "Disabled", "COLLEGE", null, false));
        DomainException assignmentError = assertThrows(DomainException.class,
                () -> organizations.replaceUserOrganization(actor(), ACTOR_ID, disabled.organizationId()));
        assertEquals("ORGANIZATION_DISABLED", assignmentError.code());

        assertEquals(1, auditCount("ACADEMIC_TERM_CREATED", "FAILED"));
        assertEquals(1, auditCount("USER_ORGANIZATION_REPLACED", "FAILED"));
    }

    private boolean moveAfterSignal(CountDownLatch ready, CountDownLatch start,
            OrganizationDtos.OrganizationUnit unit, UUID parentId) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            organizations.update(actor(), unit.organizationId(),
                    write(unit.code(), unit.displayName(), unit.unitType(), parentId));
            return true;
        } catch (DomainException exception) {
            assertEquals("ORGANIZATION_PARENT_INVALID", exception.code());
            return false;
        }
    }

    private OrganizationDtos.OrganizationUnit create(String code, String name, String type,
            UUID parentId) {
        return organizations.create(actor(), write(code, name, type, parentId));
    }

    private OrganizationDtos.OrganizationWrite write(String code, String name, String type,
            UUID parentId) {
        return new OrganizationDtos.OrganizationWrite(code, name, type, parentId, true);
    }

    private UUID parentId(UUID organizationId) {
        return jdbc.sql("SELECT parent_id FROM organization_unit WHERE id = :id")
                .param("id", organizationId.toString())
                .query((rs, row) -> new ParentReference(rs.getString("parent_id") == null
                        ? null : UUID.fromString(rs.getString("parent_id"))))
                .single().parentId();
    }

    private int auditCount(String action, String outcome) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM admin_audit_event
                WHERE actor_user_id = :actor AND action = :action AND outcome = :outcome
                """).param("actor", ACTOR_ID.toString()).param("action", action)
                .param("outcome", outcome).query(Integer.class).single();
    }

    private record ParentReference(UUID parentId) {}

    private static EduTwinPrincipal actor() {
        return new EduTwinPrincipal(ACTOR_ID, "organization-admin-test", "Organization Admin", "",
                UserRole.ADMIN, Set.of(), true);
    }
}
