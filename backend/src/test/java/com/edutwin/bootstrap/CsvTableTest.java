package com.edutwin.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvTableTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsQuotedCommasQuotesAndNewlines() throws Exception {
        Path source = temporaryDirectory.resolve("table.csv");
        Files.writeString(source, "id,value\n1,plain\n2,\"comma, quote \"\" and\nline\"\n");

        List<String> values = CsvTable.read(
                source, List.of("id", "value"), row -> row.required("value"));

        assertEquals(List.of("plain", "comma, quote \" and\nline"), values);
    }

    @Test
    void rejectsAHeaderDifference() throws Exception {
        Path source = temporaryDirectory.resolve("table.csv");
        Files.writeString(source, "wrong,value\n1,plain\n");

        assertThrows(
                IllegalStateException.class,
                () -> CsvTable.read(source, List.of("id", "value"), row -> row));
    }
}
