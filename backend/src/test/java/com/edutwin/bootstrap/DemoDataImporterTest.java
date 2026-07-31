package com.edutwin.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class DemoDataImporterTest {

    @Test
    void keepsMatchingDemoAccountPasswords() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        DemoDataImporter importer = importer(jdbc, passwordEncoder);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("TEACHER")))
                .thenReturn(List.of("existing-hash"));
        when(passwordEncoder.matches("teacher-password", "existing-hash")).thenReturn(true);

        importer.synchronizeDemoAccountPassword("TEACHER", "teacher-password");

        verify(passwordEncoder, never()).encode(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void replacesDriftedDemoAccountPasswords() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        DemoDataImporter importer = importer(jdbc, passwordEncoder);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("STUDENT")))
                .thenReturn(List.of("old-hash"));
        when(passwordEncoder.matches("student-password", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("student-password")).thenReturn("new-hash");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2_000);

        importer.synchronizeDemoAccountPassword("STUDENT", "student-password");

        verify(jdbc).update(anyString(), eq("new-hash"), eq("STUDENT"));
    }

    @Test
    void rejectsMissingImportedDemoAccounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        DemoDataImporter importer = importer(jdbc, passwordEncoder);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("TEACHER")))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> importer.synchronizeDemoAccountPassword("TEACHER", "teacher-password"));
    }

    @Test
    void reusesRegisteredSemanticDemoVersionAcrossCodeRevisions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DemoDataImporter importer = importer(jdbc, mock(PasswordEncoder.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("synthetic-demo-v6")))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("synthetic-demo-v6")))
                .thenReturn("existing-run-id");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("existing-run-id")))
                .thenReturn("manifest-sha");

        String runId = importer.findExistingProcessingRunId("manifest-sha");

        org.junit.jupiter.api.Assertions.assertEquals("existing-run-id", runId);
    }

    @Test
    void rejectsRegisteredSemanticVersionWithDifferentManifest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DemoDataImporter importer = importer(jdbc, mock(PasswordEncoder.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("synthetic-demo-v6")))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("synthetic-demo-v6")))
                .thenReturn("existing-run-id");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("existing-run-id")))
                .thenReturn("old-manifest-sha");

        assertThrows(IllegalStateException.class,
                () -> importer.findExistingProcessingRunId("new-manifest-sha"));
    }

    @Test
    void shiftsBusinessTimeToConfiguredImportDay() {
        DemoDataImporter importer = importer(
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), "2026-07-15");

        org.junit.jupiter.api.Assertions.assertEquals(
                OffsetDateTime.parse("2026-07-15T00:00:00Z"),
                importer.businessTime(OffsetDateTime.parse("2026-06-15T00:00:00Z")));
        org.junit.jupiter.api.Assertions.assertEquals(
                OffsetDateTime.parse("2026-07-22T15:00:00Z"),
                importer.businessTime(OffsetDateTime.parse("2026-06-22T15:00:00Z")));
    }

    @Test
    void shiftsInitialProjectionTimeWithTheImportBusinessClock() {
        DemoDataImporter importer = importer(
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), "2026-07-15");
        DemoInitialStateImporter initialStates = new DemoInitialStateImporter(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                importer::businessTime,
                importer.businessTime(OffsetDateTime.parse("2026-06-15T00:00:00Z")));

        org.junit.jupiter.api.Assertions.assertEquals(
                OffsetDateTime.parse("2026-07-08T22:03:01Z"),
                initialStates.businessTime(OffsetDateTime.parse("2026-06-08T22:03:01Z")));
        org.junit.jupiter.api.Assertions.assertEquals(
                OffsetDateTime.parse("2026-07-15T00:00:00Z"),
                initialStates.planScheduleTime(
                        OffsetDateTime.parse("2026-06-08T22:03:01Z"), 6));
        org.junit.jupiter.api.Assertions.assertEquals(
                OffsetDateTime.parse("2026-07-08T22:03:01Z"),
                initialStates.planScheduleTime(
                        OffsetDateTime.parse("2026-06-08T22:03:01Z"), 5));
    }

    @Test
    void acceptsOnlyACompleteRegisteredLegacySeedForUpgrade() {
        assertTrue(DemoDataImporter.isCompleteLegacySeed(new DemoDataImporter.LegacySeedState(
                2_000, 12, 20, 600, 60, 201_600, 806_400, 2_000)));
        assertFalse(DemoDataImporter.isCompleteLegacySeed(new DemoDataImporter.LegacySeedState(
                2_000, 12, 20, 600, 60, 201_599, 806_400, 2_000)));
        assertFalse(DemoDataImporter.isCompleteLegacySeed(new DemoDataImporter.LegacySeedState(
                2_000, 12, 20, 600, 60, 201_600, 806_400, 1_999)));
    }

    private DemoDataImporter importer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return importer(jdbc, passwordEncoder, "2026-07-15");
    }

    private DemoDataImporter importer(
            JdbcTemplate jdbc, PasswordEncoder passwordEncoder, String demoAsOf) {
        return new DemoDataImporter(
                jdbc,
                new ObjectMapper(),
                passwordEncoder,
                ".",
                "teacher-password",
                "student-password",
                demoAsOf);
    }
}
