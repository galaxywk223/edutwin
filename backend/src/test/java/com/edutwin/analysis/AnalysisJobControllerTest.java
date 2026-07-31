package com.edutwin.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisJobControllerTest {

    @Test
    void mapsInternalVersionedEventsToTheFrozenPublicSseContract() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("analysis.requested.v1", "job.queued");
        expected.put("analysis.started.v1", "job.processing");
        expected.put("predictions.computed.v1", "predictions.computed");
        expected.put("twin.snapshot-created.v1", "twin.snapshot-created");
        expected.put("learning-plan.created.v1", "learning-plan.created");
        expected.put("analysis.completed.v1", "job.completed");
        expected.put("analysis.failed.v1", "job.failed");

        expected.forEach((internal, publicName) -> assertEquals(
                publicName,
                AnalysisJobController.publicEventType(internal).getValue()));
    }

    @Test
    void rejectsUnknownInternalEventsInsteadOfLeakingThemPublicly() {
        assertThrows(
                IllegalStateException.class,
                () -> AnalysisJobController.publicEventType("unversioned.unknown"));
    }
}
