package com.edutwin.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(10)
@ConditionalOnProperty(prefix = "edutwin.demo-import", name = "enabled", havingValue = "true")
public class DemoCounselorBootstrap implements ApplicationRunner {
    private static final String USERNAME = "counselor-01";
    private static final UUID USER_ID = UUID.nameUUIDFromBytes(
            "edutwin-demo-counselor-01".getBytes(StandardCharsets.UTF_8));

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public DemoCounselorBootstrap(
            JdbcClient jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${edutwin.demo-import.teacher-password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) return;
        int existing = jdbc.sql("SELECT COUNT(*) FROM user_account WHERE username = :username")
                .param("username", USERNAME).query(Integer.class).single();
        if (existing == 0) {
            jdbc.sql("""
                            INSERT INTO user_account(
                                id, username, password_hash, display_name, enabled, must_change_password)
                            VALUES (:id, :username, :password, '辅导员演示', TRUE, FALSE)
                            """)
                    .param("id", USER_ID.toString()).param("username", USERNAME)
                    .param("password", passwordEncoder.encode(password)).update();
            jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'COUNSELOR')")
                    .param("id", USER_ID.toString()).update();
            jdbc.sql("INSERT INTO counselor_profile(user_id, staff_number) VALUES (:id, 'C-DEMO-01')")
                    .param("id", USER_ID.toString()).update();
        }
        UUID counselorId = jdbc.sql("SELECT id FROM user_account WHERE username = :username")
                .param("username", USERNAME)
                .query((rs, row) -> UUID.fromString(rs.getString("id"))).single();
        int scopeCount = jdbc.sql("SELECT COUNT(*) FROM counselor_scope WHERE counselor_id = :id")
                .param("id", counselorId.toString()).query(Integer.class).single();
        if (scopeCount > 0) return;
        jdbc.sql("""
                        INSERT INTO counselor_scope(
                            counselor_id, scope_type, scope_key, college, major,
                            cohort_year, class_name, created_by)
                        SELECT :id, 'CLASS',
                               CONCAT('CLASS|', sp.college, '|', sp.major, '|', sp.cohort_year, '|', sp.class_name),
                               sp.college, sp.major, sp.cohort_year, sp.class_name, :id
                        FROM student_profile sp
                        WHERE sp.college IS NOT NULL AND sp.major IS NOT NULL
                          AND sp.cohort_year IS NOT NULL AND sp.class_name IS NOT NULL
                        GROUP BY sp.college, sp.major, sp.cohort_year, sp.class_name
                        ORDER BY sp.cohort_year DESC, sp.major, sp.class_name
                        LIMIT 2
                        """)
                .param("id", counselorId.toString()).update();
    }
}
