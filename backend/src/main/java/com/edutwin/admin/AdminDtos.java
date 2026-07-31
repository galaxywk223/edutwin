package com.edutwin.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}

    public record Overview(int enabledUsers, int teachers, int students, int counselors,
            int enabledDatasets, int modelDeployments, OffsetDateTime generatedAt) {}
    public record AdminUser(UUID userId, String username, String displayName, String role, List<String> roles,
            boolean enabled, boolean mustChangePassword, String originType,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record UserList(List<AdminUser> items, int total) {}
    public record UserCreateRequest(String username, String displayName, String role,
            List<String> roles, String password) {}
    public record UserUpdateRequest(String displayName, String role, List<String> roles, Boolean enabled) {}
    public record TemporaryPassword(String temporaryPassword, boolean mustChangePassword) {}

    public record UserImportError(String file, String sheet, int row, String field,
            String code, String message) {}
    public record UserImportPreview(String fileSha256, String format, int userRows,
            int membershipRows, int createCount, int updateCount,
            List<UserImportError> errors, boolean valid) {}
    public record TemporaryCredential(String username, String displayName, String role,
            String temporaryPassword) {}
    public record UserImportResult(String fileSha256, int createdCount, int updatedCount,
            int membershipCount, List<TemporaryCredential> credentials) {}

    public record CounselorScope(String scopeType, String college, String major,
            int cohortYear, String className) {}
    public record CounselorScopeList(List<CounselorScope> items, int total) {}
    public record CounselorScopeReplaceRequest(List<CounselorScope> items) {}
    public record StudentGroup(String scopeType, String college, String major,
            int cohortYear, String className, int studentCount) {}
    public record StudentGroupList(List<StudentGroup> items, int total) {}

    public record DatasetVersion(String versionId, String sourceKey, String sourceName,
            String lifecycleStatus, long rowCount, String manifestSha256,
            OffsetDateTime processedAt, boolean referenced) {}
    public record DatasetList(List<DatasetVersion> items, int total) {}
    public record DatasetImpact(String versionId, int courseReferences,
            int activeModelReferences, int rollbackModelReferences,
            boolean canDeprecate, boolean canRetire, List<String> blockers) {}
    public record StatusRequest(String status, String reason) {
        public StatusRequest(String status) { this(status, null); }
    }

    public record ModelDeployment(String taskName, String activeVersionId, String rollbackVersionId,
            OffsetDateTime deployedAt, String deployedBy) {}
    public record ModelDeploymentList(List<ModelDeployment> items, int total) {}
    public record ModelRollbackRequest(String expectedActiveVersionId,
            String targetVersionId, String reason) {}
    public record ModelRollbackResult(ModelDeployment deployment, List<String> affectedModules) {}

    public record AiConfiguration(
            String source,
            long revision,
            boolean enabled,
            String apiBaseUrl,
            String model,
            boolean apiKeyConfigured,
            boolean writeAvailable,
            String lastTestStatus,
            OffsetDateTime lastTestedAt,
            Long lastTestLatencyMs,
            OffsetDateTime updatedAt,
            String updatedBy) {}
    public record AiConfigurationUpdateRequest(
            long expectedRevision,
            boolean enabled,
            String apiBaseUrl,
            String model,
            String apiKey) {}
    public record AiConfigurationRestoreRequest(long expectedRevision) {}
    public record AiConfigurationActivationResult(
            AiConfiguration configuration,
            String testStatus,
            Long testLatencyMs) {}

    public record AuditEvent(UUID id, UUID actorUserId, String actorDisplayName, String activeRole,
            String action, String targetType, String targetId, String outcome,
            String reason, String errorCode, String beforeJson, String afterJson,
            String metadataJson, UUID correlationId, OffsetDateTime createdAt) {}
    public record AuditList(List<AuditEvent> items, long total, int page, int size) {}
}
