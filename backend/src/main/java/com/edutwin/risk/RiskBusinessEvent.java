package com.edutwin.risk;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RiskBusinessEvent(
        String action,
        UUID actorId,
        UUID caseId,
        UUID targetId,
        Map<String, String> attributes,
        Instant occurredAt) {

    public RiskBusinessEvent {
        attributes = Map.copyOf(attributes);
    }
}
