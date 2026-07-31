package com.edutwin.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
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
class AdminLastAdministratorIntegrationTest {
    private static final UUID FIRST = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("71000000-0000-0000-0000-000000000002");

    @Autowired AdminService service;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('ADMIN','Administrator')").update();
        insertAdmin(FIRST, "last-admin-first", "First Admin");
        insertAdmin(SECOND, "last-admin-second", "Second Admin");
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id IN (:first, :second)")
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
        jdbc.sql("DELETE FROM user_notification WHERE recipient_user_id IN (:first, :second)")
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id IN (:first, :second)")
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id IN (:first, :second)")
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id IN (:first, :second)")
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
    }

    @Test
    void concurrentSelfDisablesLeaveOneEnabledAdministrator() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> disableAfterSignal(ready, start, actor(FIRST, "last-admin-first")));
            var second = executor.submit(() -> disableAfterSignal(ready, start, actor(SECOND, "last-admin-second")));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }

        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                """).query(Integer.class).single());
    }

    private boolean disableAfterSignal(CountDownLatch ready, CountDownLatch start,
            EduTwinPrincipal actor) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.updateUser(actor, actor.userId(),
                    new AdminDtos.UserUpdateRequest(null, null, null, false));
            return true;
        } catch (DomainException exception) {
            assertEquals("LAST_ADMIN_REQUIRED", exception.code());
            return false;
        }
    }

    private void insertAdmin(UUID id, String username, String displayName) {
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, :username, 'disabled', :name, TRUE, 'ADMIN')
                """).param("id", id.toString()).param("username", username)
                .param("name", displayName).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id,'ADMIN')")
                .param("id", id.toString()).update();
    }

    private static EduTwinPrincipal actor(UUID id, String username) {
        return new EduTwinPrincipal(id, username, username, "", UserRole.ADMIN, Set.of(), true);
    }
}
