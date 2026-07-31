package com.edutwin.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnalysisWorkItemRepositoryTest {

    @Test
    void treatsMissingAiConfigurationVersionAsOptional() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<UUID> query = mock(JdbcClient.MappedQuerySpec.class);
        UUID jobId = UUID.randomUUID();

        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.param("jobId", jobId.toString())).thenReturn(statement);
        when(statement.query(UUID.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        AnalysisWorkItemRepository repository =
                new AnalysisWorkItemRepository(jdbcClient, mock(AnalysisJobRepository.class));

        assertTrue(repository.findAiConfigurationVersionId(jobId).isEmpty());
    }

    @Test
    void returnsConfiguredAiConfigurationVersion() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<UUID> query = mock(JdbcClient.MappedQuerySpec.class);
        UUID jobId = UUID.randomUUID();
        UUID configurationVersionId = UUID.randomUUID();

        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.param("jobId", jobId.toString())).thenReturn(statement);
        when(statement.query(UUID.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.of(configurationVersionId));

        AnalysisWorkItemRepository repository =
                new AnalysisWorkItemRepository(jdbcClient, mock(AnalysisJobRepository.class));

        assertEquals(
                configurationVersionId,
                repository.findAiConfigurationVersionId(jobId).orElseThrow());
    }
}
