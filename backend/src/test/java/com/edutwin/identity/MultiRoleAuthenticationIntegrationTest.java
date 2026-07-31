package com.edutwin.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MultiRoleAuthenticationIntegrationTest {
    private static final UUID USER_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String USERNAME = "multi-role-auth-test";
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('STUDENT','Student'),('TEACHER','Teacher')").update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                VALUES (:id, :username, :password, 'Multi Role', TRUE)
                """).param("id", USER_ID.toString()).param("username", USERNAME)
                .param("password", passwordEncoder.encode("multi-role-password")).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id,'TEACHER'),(:id,'STUDENT')")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("INSERT INTO student_profile(user_id, synthetic, split_name) VALUES (:id,FALSE,'test')")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("INSERT INTO teacher_profile(user_id, staff_key) VALUES (:id,'multi-role-test')")
                .param("id", USER_ID.toString()).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM teacher_profile WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM student_profile WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id = :id")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", USER_ID.toString()).update();
    }

    @Test
    void lowestPrivilegeRoleIsDefaultAndSwitchInvalidatesOldToken() throws Exception {
        String login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"multi-role-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andReturn().getResponse().getContentAsString();
        String oldToken = objectMapper.readTree(login).get("accessToken").asText();

        String switched = mockMvc.perform(post("/api/v1/auth/role")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TEACHER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.activeRole").value("TEACHER"))
                .andExpect(jsonPath("$.user.availableRoles.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String newToken = objectMapper.readTree(switched).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("TEACHER"));

        mockMvc.perform(post("/api/v1/auth/role")
                        .header("Authorization", "Bearer " + newToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        assertAudit("SUCCEEDED", null);
        assertAudit("FAILED", "AUTH_ROLE_NOT_GRANTED");
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.sql(
                "SELECT COUNT(*) FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", USER_ID.toString()).query(Integer.class).single());
    }

    private void assertAudit(String outcome, String errorCode) {
        var event = jdbc.sql("""
                SELECT error_code, before_json, after_json FROM admin_audit_event
                WHERE actor_user_id = :id AND action = 'ACTIVE_ROLE_CHANGED' AND outcome = :outcome
                """).param("id", USER_ID.toString()).param("outcome", outcome)
                .query((rs, row) -> new AuditFact(rs.getString("error_code"),
                        rs.getString("before_json"), rs.getString("after_json"))).single();
        org.junit.jupiter.api.Assertions.assertEquals(errorCode, event.errorCode());
        org.junit.jupiter.api.Assertions.assertFalse(event.beforeJson().contains("multi-role-password"));
        if (event.afterJson() != null) {
            org.junit.jupiter.api.Assertions.assertFalse(event.afterJson().contains("multi-role-password"));
        }
    }

    private record AuditFact(String errorCode, String beforeJson, String afterJson) {}
}
