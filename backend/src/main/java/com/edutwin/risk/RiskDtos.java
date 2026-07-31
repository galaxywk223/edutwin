package com.edutwin.risk;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class RiskDtos {
    private RiskDtos() {}

    public record CaseSummary(
            UUID caseId,
            UUID courseId,
            UUID studentId,
            String studentName,
            String courseTitle,
            String triggerType,
            String riskBand,
            String title,
            String summary,
            String status,
            String priority,
            UUID assignedTo,
            String assigneeName,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime resolvedAt,
            OffsetDateTime closedAt) {}

    public record CasePage(List<CaseSummary> items, int total, int page, int size) {}

    public record Assignment(
            UUID assignmentId,
            UUID fromAssignee,
            UUID toAssignee,
            UUID changedBy,
            String reason,
            OffsetDateTime createdAt) {}

    public record InternalNote(
            UUID noteId, UUID authorId, String authorName, String body, OffsetDateTime createdAt) {}

    public record Feedback(
            UUID feedbackId, UUID studentId, String body, OffsetDateTime createdAt) {}

    public record ActionItem(
            UUID actionItemId,
            UUID caseId,
            UUID studentId,
            String title,
            String description,
            String status,
            OffsetDateTime dueAt,
            String resultSummary,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            List<Feedback> feedback) {}

    public record CaseDetail(
            CaseSummary riskCase,
            List<Assignment> assignments,
            List<InternalNote> internalNotes,
            List<ActionItem> actionItems) {}

    public record CreateCaseRequest(UUID courseId, UUID studentId, String title, String summary) {}

    public record AssignmentRequest(UUID assigneeId, String reason, Long expectedVersion) {}

    public record StatusRequest(String targetStatus, String reason, Long expectedVersion) {}

    public record NoteRequest(String body) {}

    public record ActionRequest(String title, String description, OffsetDateTime dueAt) {}

    public record ActionStatusRequest(String targetStatus, String resultSummary) {}

    public record FeedbackRequest(String body) {}

    public record StudentActionProjection(
            UUID actionItemId,
            UUID caseId,
            UUID courseId,
            String courseTitle,
            String title,
            String description,
            String status,
            OffsetDateTime dueAt,
            String resultSummary,
            List<Feedback> feedback) {}
}
