package com.edutwin.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class AssistantServiceIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("78000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("78000000-0000-0000-0000-000000000002");
    private static final UUID MESSAGE_ID = UUID.fromString("78000000-0000-0000-0000-000000000003");
    private static final UUID TOOL_CALL_ID = UUID.fromString("78000000-0000-0000-0000-000000000004");

    @Autowired JdbcClient jdbc;
    @Autowired AssistantService service;
    @Autowired AssistantTools tools;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT IGNORE INTO role_definition(code, description) VALUES ('STUDENT','Student')").update();
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                VALUES (:id, 'assistant-service-test', 'disabled', 'Assistant Service Test', TRUE, 'STUDENT')
                """).param("id", USER_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'STUDENT')")
                .param("id", USER_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO assistant_conversation(id, user_id, active_role, title)
                VALUES (:id, :userId, 'STUDENT', 'Integration test')
                """).param("id", CONVERSATION_ID.toString()).param("userId", USER_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO assistant_message(id, conversation_id, message_role, status, content)
                VALUES (:id, :conversationId, 'ASSISTANT', 'PROCESSING', 'partial response')
                """).param("id", MESSAGE_ID.toString()).param("conversationId", CONVERSATION_ID.toString()).update();
        insertEvent(2, "message.delta", "{\"text\":\"second\"}");
        insertEvent(1, "message.started", "{}");
        jdbc.sql("""
                INSERT INTO assistant_tool_call(
                    id, message_id, call_index, tool_name, request_sha256,
                    result_sha256, result_json, status, started_at, completed_at)
                VALUES (:id, :messageId, 1, 'getMyCourses', REPEAT('a', 64),
                        REPEAT('b', 64), '{\"private\":true}', 'SUCCEEDED',
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("id", TOOL_CALL_ID.toString()).param("messageId", MESSAGE_ID.toString()).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM ai_invocation WHERE assistant_message_id = :messageId")
                .param("messageId", MESSAGE_ID.toString()).update();
        jdbc.sql("DELETE FROM assistant_tool_call WHERE message_id = :messageId")
                .param("messageId", MESSAGE_ID.toString()).update();
        jdbc.sql("DELETE FROM assistant_message_event WHERE message_id = :messageId")
                .param("messageId", MESSAGE_ID.toString()).update();
        jdbc.sql("DELETE FROM assistant_message WHERE conversation_id = :conversationId")
                .param("conversationId", CONVERSATION_ID.toString()).update();
        jdbc.sql("DELETE FROM assistant_conversation WHERE id = :id")
                .param("id", CONVERSATION_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", USER_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", USER_ID.toString()).update();
    }

    @Test
    void recoveryEventsAreStrictlyOrderedAndResumeAfterLastSequence() {
        assertEquals(
                java.util.List.of(1L, 2L),
                service.events(MESSAGE_ID, 0).stream().map(AssistantDtos.EventEnvelope::sequence).toList());
        assertEquals(
                java.util.List.of(2L),
                service.events(MESSAGE_ID, 1).stream().map(AssistantDtos.EventEnvelope::sequence).toList());
    }

    @Test
    void deletingConversationErasesBodiesEventsAndToolResults() {
        service.deleteConversation(principal(), CONVERSATION_ID);

        assertEquals("DELETED", scalar("SELECT status FROM assistant_message WHERE id = :id", MESSAGE_ID));
        assertNull(scalar("SELECT content FROM assistant_message WHERE id = :id", MESSAGE_ID));
        assertEquals(0L, count("SELECT COUNT(*) FROM assistant_message_event WHERE message_id = :id"));
        assertNull(scalar("SELECT result_json FROM assistant_tool_call WHERE id = :id", TOOL_CALL_ID));
        assertThrows(DomainException.class, () -> service.message(principal(), MESSAGE_ID));
    }

    @Test
    void studentProfileAndPlanToolsRejectCoursesOutsideActiveEnrollment() {
        AssistantTools.StudentTools studentTools = (AssistantTools.StudentTools) tools.create(
                principal(), MESSAGE_ID, new AssistantTools.ToolEventSink() {
                    @Override
                    public void started(int callIndex, UUID toolCallId, String toolName) {}

                    @Override
                    public void completed(
                            int callIndex, UUID toolCallId, String toolName, boolean succeeded) {}
                }).tools();
        String unauthorizedCourseId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class,
                () -> studentTools.getMyLearningProfile(unauthorizedCourseId));
        assertThrows(IllegalArgumentException.class,
                () -> studentTools.getMyLearningPlan(unauthorizedCourseId));
    }

    private void insertEvent(long sequence, String type, String data) {
        jdbc.sql("""
                INSERT INTO assistant_message_event(id, message_id, sequence_no, event_type, data_json)
                VALUES (:id, :messageId, :sequence, :type, CAST(:data AS JSON))
                """).param("id", UUID.randomUUID().toString()).param("messageId", MESSAGE_ID.toString())
                .param("sequence", sequence).param("type", type).param("data", data).update();
    }

    private String scalar(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id.toString()).query(String.class).optional().orElse(null);
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("id", MESSAGE_ID.toString()).query(Long.class).single();
    }

    private EduTwinPrincipal principal() {
        return new EduTwinPrincipal(
                USER_ID, "assistant-service-test", "Assistant Service Test", "",
                UserRole.STUDENT, Set.of(), true);
    }
}
