package com.edutwin.bootstrap;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(100)
public class AdminAccountBootstrap implements ApplicationRunner {

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminAccountBootstrap(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            @Value("${edutwin.admin-bootstrap.username:}") String username,
            @Value("${edutwin.admin-bootstrap.password:}") String password,
            @Value("${edutwin.admin-bootstrap.display-name:System Administrator}") String displayName) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.username = username.trim();
        this.password = password;
        this.displayName = displayName.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || displayName.isBlank() || password.length() < 16) {
            throw new IllegalStateException(
                    "Admin bootstrap requires a username, display name, and password of at least 16 characters.");
        }
        var existing = jdbcClient.sql("""
                        SELECT u.id, MAX(CASE WHEN ur.role_code = 'ADMIN' THEN 1 ELSE 0 END) is_admin
                        FROM user_account u
                        JOIN user_role ur ON ur.user_id = u.id
                        WHERE u.username = :username
                        GROUP BY u.id
                        """)
                .param("username", username)
                .query((resultSet, rowNum) -> resultSet.getBoolean("is_admin"))
                .optional();
        if (existing.isPresent()) {
            if (!existing.get()) {
                throw new IllegalStateException(
                        "The configured admin username already belongs to a non-admin account.");
            }
            return;
        }
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO user_account(
                            id, username, password_hash, display_name, enabled, must_change_password)
                        VALUES (:id, :username, :passwordHash, :displayName, TRUE, FALSE)
                        """)
                .param("id", id.toString())
                .param("username", username)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("displayName", displayName)
                .update();
        jdbcClient.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'ADMIN')")
                .param("id", id.toString())
                .update();
        jdbcClient.sql("UPDATE user_account SET last_active_role = 'ADMIN' WHERE id = :id")
                .param("id", id.toString()).update();
        jdbcClient.sql("INSERT INTO user_profile(user_id) VALUES (:id)")
                .param("id", id.toString()).update();
    }
}
