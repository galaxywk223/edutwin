package com.edutwin.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SyntheticMatchMigrationTest {

    @Test
    void syntheticMatchPersistsOnlyDomainSeparatedStudentHashes() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V1__create_edutwin_schema.sql"));
        int tableStart = migration.indexOf("CREATE TABLE synthetic_match");
        int tableEnd = migration.indexOf("CREATE TABLE model_version", tableStart);
        String table = migration.substring(tableStart, tableEnd);

        assertTrue(table.contains("assistments_user_sha256 CHAR(64) NOT NULL"));
        assertTrue(table.contains("oulad_student_sha256 CHAR(64) NOT NULL"));
        assertTrue(table.contains("assistments_user_sha256 REGEXP '^[0-9a-f]{64}$'"));
        assertTrue(table.contains("oulad_student_sha256 REGEXP '^[0-9a-f]{64}$'"));
        assertFalse(table.contains("assistments_user_key"));
        assertFalse(table.contains("oulad_student_key"));
    }
}
