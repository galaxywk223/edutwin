package com.edutwin.planning;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PlanningDtos {
    private PlanningDtos() {}

    public record PlanTaskProjection(
            UUID planTaskId,
            UUID planId,
            UUID questionId,
            String questionTitle,
            UUID skillId,
            String skillName,
            String taskType,
            String reasonCode,
            int targetCount,
            int completedCount,
            String status,
            OffsetDateTime dueAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime skippedAt,
            String skipReason,
            String practicePath) {}

    public record PlanLifecycle(
            UUID planId,
            UUID courseId,
            UUID studentId,
            long version,
            String status,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            List<PlanTaskProjection> tasks) {}

    public record StartTaskRequest(UUID expectedPlanId) {}

    public record SkipTaskRequest(String reason, UUID expectedPlanId) {}

    public record PracticeTask(
            UUID planTaskId,
            UUID planId,
            UUID courseId,
            UUID questionId,
            String questionTitle,
            String prompt,
            String answerType,
            String optionsJson,
            UUID skillId,
            String skillName,
            int targetCount,
            int completedCount) {}
}
