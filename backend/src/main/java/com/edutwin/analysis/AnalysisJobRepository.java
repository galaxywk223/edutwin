package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisJob;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository {

    Optional<AnalysisJob> findById(UUID jobId);

    List<JobEvent> findEventsAfter(UUID jobId, long sequenceExclusive, int limit);

    record JobEvent(long sequence, String eventType, String payload, OffsetDateTime occurredAt) {}
}
