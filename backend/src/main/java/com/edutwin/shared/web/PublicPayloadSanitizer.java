package com.edutwin.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PublicPayloadSanitizer {
    private static final Set<String> HIDDEN_FIELDS = Set.of(
            "trace", "snapshotId", "basedOnSnapshotId",
            "dataVersions", "modelVersions", "requestedModelVersions", "effectiveModelVersions",
            "dataVersion", "datasetVersion", "modelVersion", "masteryModelVersion",
            "nextModelVersion", "riskModelVersion", "providerModel", "generationMode",
            "artifactSha256", "manifestSha256", "sourceSha256", "configSha256",
            "processingConfigSha256", "dependencyLockSha256", "processingRunId",
            "calibratorVersion", "featureContractVersion", "ruleEngineVersion",
            "calibrated", "baseValue",
            "degraded", "degradationReasons", "toolCallVerified", "selectedByValidation",
            "estimator");

    private final ObjectMapper objectMapper;

    public PublicPayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitize(Object value) {
        JsonNode root = objectMapper.valueToTree(value);
        removeHidden(root);
        return root;
    }

    private void removeHidden(JsonNode node) {
        if (node instanceof ObjectNode object) {
            HIDDEN_FIELDS.forEach(object::remove);
            object.elements().forEachRemaining(this::removeHidden);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::removeHidden);
        }
    }
}
