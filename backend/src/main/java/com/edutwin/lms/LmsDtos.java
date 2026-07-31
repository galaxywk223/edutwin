package com.edutwin.lms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LmsDtos {

    private LmsDtos() {}

    public record LmsCourse(
            UUID courseId,
            String code,
            String title,
            String termLabel,
            String description,
            String status,
            LocalDate startsOn,
            int sectionCount,
            int assessmentCount,
            int enrolledStudentCount,
            String college,
            String department,
            BigDecimal credits,
            String instructorName,
            String assignmentRole) {}

    public record LmsCourseList(List<LmsCourse> items, int total) {}

    public record CourseMutationRequest(
            String code,
            String title,
            String termLabel,
            BigDecimal credits,
            String description,
            LocalDate startsOn) {}

    public record StatusChangeRequest(
            String status,
            String expectedCurrentStatus,
            String targetStatus,
            String reason) {
        public String requestedStatus() {
            return targetStatus == null || targetStatus.isBlank() ? status : targetStatus;
        }
    }

    public record SectionMutationRequest(String title, String description, String status) {}

    public record LessonMutationRequest(
            String title,
            String summary,
            String body,
            String resourceUrl,
            String status) {}

    public record LmsLesson(
            UUID lessonId,
            String title,
            String summary,
            String body,
            String resourceUrl,
            int position,
            String status,
            boolean completed) {}

    public record LmsSection(
            UUID sectionId,
            String title,
            String description,
            int position,
            String status,
            List<LmsLesson> lessons) {}

    public record CourseOutline(
            LmsCourse course,
            List<LmsSection> sections,
            List<AssessmentSummary> assessments,
            int completedLessonCount,
            int totalLessonCount) {}

    public record RosterStudent(
            UUID studentId,
            String username,
            String displayName,
            String studentNumber,
            String college,
            String major,
            int cohortYear,
            String className,
            boolean enrolled,
            String enrollmentStatus) {}

    public record RosterList(List<RosterStudent> items, int total, int enrolledCount) {}

    public record EnrollmentUpdateRequest(boolean enrolled, String reason) {}

    public record LifecycleImpactPreview(
            String entityType,
            UUID entityId,
            String currentStatus,
            String targetStatus,
            int affectedSectionCount,
            int affectedLessonCount,
            int affectedAssessmentCount,
            int affectedStudentCount,
            int affectedSubmissionCount,
            boolean confirmationRequired) {}

    public record ContentOrderRequest(List<UUID> itemIds) {}

    public record AssessmentDueDateRequest(OffsetDateTime dueAt, String reason) {}

    public record AssessmentCancelRequest(String reason) {}

    public record AssessmentCloneRequest(String title, OffsetDateTime dueAt) {}

    public record AttemptRequestCreate(
            String requestType,
            String reason,
            OffsetDateTime requestedDueAt,
            UUID studentId) {}

    public record AttemptRequestDecision(
            String decision,
            String reason,
            OffsetDateTime personalDueAt) {}

    public record AttemptRequest(
            UUID requestId,
            UUID assessmentId,
            UUID studentId,
            String requestType,
            String status,
            String reason,
            OffsetDateTime requestedDueAt,
            OffsetDateTime personalDueAt,
            UUID requestedBy,
            UUID decidedBy,
            String decisionReason,
            OffsetDateTime requestedAt,
            OffsetDateTime decidedAt,
            boolean consumed) {}

    public record AttemptRequestList(List<AttemptRequest> items, int total) {}

    public record AssessmentOption(String choiceId, String label) {}

    public record AssessmentQuestionRequest(
            String prompt,
            List<AssessmentOption> options,
            String correctChoiceId,
            BigDecimal points,
            List<UUID> skillIds) {}

    public record KnowledgeSkillSummary(UUID skillId, String skillCode, String name,
            String contentOrigin) {}

    public record KnowledgeSkillList(List<KnowledgeSkillSummary> items, int total) {}

    public record AssessmentMutationRequest(
            String title,
            String description,
            String assessmentType,
            OffsetDateTime dueAt,
            List<AssessmentQuestionRequest> questions) {}

    public record AssessmentQuestion(
            UUID questionId,
            String prompt,
            List<AssessmentOption> options,
            BigDecimal points,
            int position) {}

    public record AssessmentSummary(
            UUID assessmentId,
            UUID courseId,
            String title,
            String description,
            String assessmentType,
            String status,
            OffsetDateTime dueAt,
            int questionCount,
            int submissionCount,
            boolean submitted,
            BigDecimal score,
            BigDecimal maxScore,
            String learnerState,
            int attemptCount,
            int remainingAttemptCount) {}

    public record AssessmentList(List<AssessmentSummary> items, int total) {}

    public record AssessmentDetail(
            UUID assessmentId,
            UUID courseId,
            String title,
            String description,
            String assessmentType,
            String status,
            OffsetDateTime dueAt,
            int questionCount,
            int submissionCount,
            boolean submitted,
            BigDecimal score,
            BigDecimal maxScore,
            List<AssessmentQuestion> questions) {}

    public record AssessmentAnswerRequest(UUID questionId, String selectedChoiceId) {}

    public record AssessmentSubmissionRequest(List<AssessmentAnswerRequest> answers) {}

    public record AssessmentAnswerResult(
            UUID questionId,
            String selectedChoiceId,
            String correctChoiceId,
            boolean correct,
            BigDecimal pointsAwarded) {}

    public record AssessmentResult(
            UUID submissionId,
            UUID assessmentId,
            UUID studentId,
            BigDecimal score,
            BigDecimal maxScore,
            BigDecimal percentage,
            OffsetDateTime submittedAt,
            int attemptNumber,
            String attemptType,
            boolean validForGrade,
            List<AssessmentAnswerResult> answers) {}

    public record AssessmentAttemptList(
            List<AssessmentResult> items,
            int total,
            UUID currentSubmissionId,
            BigDecimal currentScore) {}

    public record GradeItem(
            UUID assessmentId,
            String title,
            UUID studentId,
            String displayName,
            boolean submitted,
            BigDecimal score,
            BigDecimal maxScore,
            BigDecimal percentage,
            OffsetDateTime submittedAt,
            int attemptCount) {}

    public record GradeList(List<GradeItem> items, int total) {}
}
