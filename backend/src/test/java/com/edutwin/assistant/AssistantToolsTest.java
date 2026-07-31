package com.edutwin.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class AssistantToolsTest {

    private final AssistantTools tools = new AssistantTools(
            mock(JdbcClient.class), new ObjectMapper());
    private final AssistantTools.ToolEventSink sink = new AssistantTools.ToolEventSink() {
        @Override
        public void started(int callIndex, UUID toolCallId, String toolName) {}

        @Override
        public void completed(
                int callIndex, UUID toolCallId, String toolName, boolean succeeded) {}
    };

    @Test
    void eachActiveRoleReceivesOnlyItsReadOnlyToolSet() {
        assertEquals("StudentTools", toolClass(UserRole.STUDENT));
        assertEquals("TeacherTools", toolClass(UserRole.TEACHER));
        assertEquals("CounselorTools", toolClass(UserRole.COUNSELOR));
        assertEquals("AdminTools", toolClass(UserRole.ADMIN));

        Set<String> counselorMethods = methodNames(UserRole.COUNSELOR);
        assertTrue(counselorMethods.contains("compareClasses"));
        assertFalse(counselorMethods.stream().anyMatch(name -> name.toLowerCase().contains("answer")));

        Set<String> adminMethods = methodNames(UserRole.ADMIN);
        assertTrue(adminMethods.contains("getDatasetImpact"));
        assertFalse(adminMethods.stream().anyMatch(name -> name.toLowerCase().contains("learning")));
    }

    private String toolClass(UserRole role) {
        return tools.create(principal(role), UUID.randomUUID(), sink)
                .tools().getClass().getSimpleName();
    }

    private Set<String> methodNames(UserRole role) {
        return Arrays.stream(tools.create(principal(role), UUID.randomUUID(), sink)
                        .tools().getClass().getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    private static EduTwinPrincipal principal(UserRole role) {
        return new EduTwinPrincipal(
                UUID.randomUUID(), "assistant-test", "Assistant Test", "",
                role, Set.of(), true);
    }
}
