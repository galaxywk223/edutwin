package com.edutwin.shared.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicPayloadSanitizerTest {
    @Test
    void recursivelyRemovesTechnicalTransparencyWithoutRemovingBusinessIds() {
        PublicPayloadSanitizer sanitizer = new PublicPayloadSanitizer(new ObjectMapper());
        var result = sanitizer.sanitize(Map.of(
                "jobId", "job-1",
                "studentId", "student-1",
                "trace", Map.of("snapshotId", "snapshot-1", "dataVersions", List.of("v1")),
                "risk", Map.of("probability", 0.5, "modelVersion", "risk-v1", "degraded", false),
                "items", List.of(Map.of("manifestSha256", "a".repeat(64), "value", 1))));

        String json = result.toString();
        assertTrue(json.contains("jobId"));
        assertTrue(json.contains("studentId"));
        assertTrue(json.contains("probability"));
        assertFalse(json.contains("trace"));
        assertFalse(json.contains("snapshotId"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("manifestSha256"));
        assertFalse(json.contains("degraded"));
    }
}
