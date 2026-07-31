package com.edutwin.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

record DemoSeedRegistry(
        int schemaVersion,
        ProcessingRun processingRun,
        List<Dataset> datasets,
        List<Model> models,
        List<Deployment> deployments) {

    record ProcessingRun(
            String id,
            String pipelineVersion,
            String gitTreeSha256,
            String configSha256,
            int randomSeed,
            String status,
            String startedAt,
            String completedAt,
            String outputManifestSha256) {}

    record Dataset(Source source, Version version, List<SourceFile> files) {}

    record Source(
            String id,
            String sourceKey,
            String name,
            String officialUrl,
            String licenseName,
            String licenseUrl,
            String citationText) {}

    record Version(
            String versionId,
            String sourceSha256,
            String schemaSha256,
            String processingConfigSha256,
            String manifestSha256,
            long rowCount,
            String processedAt,
            JsonNode manifestJson) {}

    record SourceFile(
            String fileName,
            String downloadUrl,
            String sha256,
            long sizeBytes) {}

    record Model(
            String versionId,
            String modelFamily,
            String taskName,
            String datasetVersionId,
            String status,
            int randomSeed,
            JsonNode configJson,
            String featureContractSha256,
            String calibratorType,
            String calibratorSha256,
            String manifestSha256,
            boolean selected,
            String frozenAt,
            String testEvaluatedAt,
            List<Metric> metrics,
            List<Artifact> artifacts) {}

    record Metric(
            String splitName,
            String metricName,
            BigDecimal metricValue,
            String measuredAt) {}

    record Artifact(
            String artifactRole,
            String artifactUri,
            String sha256,
            long sizeBytes,
            JsonNode dependencyVersions) {}

    record Deployment(
            String taskName,
            String activeVersionId,
            String rollbackVersionId,
            String deployedAt,
            String deployedBy) {}
}
