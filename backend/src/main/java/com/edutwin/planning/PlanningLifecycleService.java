package com.edutwin.planning;

import com.edutwin.api.model.LearningPlan;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningLifecycleService {
    private final JdbcClient jdbc;
    private final PlanningReadService readService;

    public PlanningLifecycleService(JdbcClient jdbc, PlanningReadService readService) {
        this.jdbc = jdbc;
        this.readService = readService;
    }

    @Scheduled(fixedDelayString = "${edutwin.planning.expiration-delay:PT1M}")
    @Transactional
    public void expirePlans() {
        jdbc.sql("""
                        UPDATE learning_plan SET status = 'EXPIRED', expired_at = CURRENT_TIMESTAMP(6)
                        WHERE status = 'ACTIVE' AND valid_until < CURRENT_TIMESTAMP(6)
                        """).update();
    }

    public PlanningDtos.PlanLifecycle current(UUID courseId, UUID studentId) {
        UUID planId = currentPlanId(courseId, studentId, false);
        expireIfNeeded(planId);
        return lifecycle(planId);
    }

    @Transactional
    public PlanningDtos.PlanLifecycle startTask(
            EduTwinPrincipal student, UUID courseId, UUID studentId, UUID taskId,
            PlanningDtos.StartTaskRequest request) {
        requireSelf(student, studentId);
        UUID planId = requireExpectedCurrent(courseId, studentId, request == null ? null : request.expectedPlanId());
        requireActive(planId);
        int updated = jdbc.sql("""
                        UPDATE learning_plan_item SET status = 'IN_PROGRESS',
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6))
                        WHERE id = :taskId AND learning_plan_id = :planId AND status = 'PENDING'
                        """).param("taskId", taskId.toString()).param("planId", planId.toString()).update();
        if (updated == 0 && !taskHasStatus(taskId, planId, "IN_PROGRESS")) {
            throw conflict("PLAN_TASK_TRANSITION_INVALID", "Only a pending task can be started.");
        }
        return lifecycle(planId);
    }

    @Transactional
    public PlanningDtos.PlanLifecycle skipTask(
            EduTwinPrincipal student, UUID courseId, UUID studentId, UUID taskId,
            PlanningDtos.SkipTaskRequest request) {
        requireSelf(student, studentId);
        UUID planId = requireExpectedCurrent(courseId, studentId, request == null ? null : request.expectedPlanId());
        requireActive(planId);
        String reason = required(request == null ? null : request.reason(), 300);
        int updated = jdbc.sql("""
                        UPDATE learning_plan_item SET status = 'SKIPPED', skip_reason = :reason,
                            skipped_at = CURRENT_TIMESTAMP(6)
                        WHERE id = :taskId AND learning_plan_id = :planId
                          AND status IN ('PENDING', 'IN_PROGRESS')
                        """).param("reason", reason).param("taskId", taskId.toString())
                .param("planId", planId.toString()).update();
        if (updated != 1) {
            throw conflict("PLAN_TASK_TRANSITION_INVALID", "Only a pending or in-progress task can be skipped.");
        }
        completePlanWhenTerminal(planId);
        return lifecycle(planId);
    }

    @Transactional
    public PlanningDtos.PracticeTask practiceTask(
            EduTwinPrincipal student, UUID courseId, UUID taskId) {
        UUID studentId = student.userId();
        UUID planId = currentPlanId(courseId, studentId, true);
        requireActive(planId);
        PlanningDtos.PracticeTask task = jdbc.sql("""
                        SELECT item.id, item.learning_plan_id, plan.course_id, item.question_id,
                               item.title question_title, q.prompt_text, q.answer_type, q.options_json,
                               item.skill_id, skill.name skill_name,
                               item.target_count, item.completed_count
                        FROM learning_plan_item item
                        JOIN learning_plan plan ON plan.id = item.learning_plan_id
                        JOIN question q ON q.id = item.question_id
                        JOIN knowledge_skill skill ON skill.id = item.skill_id
                        WHERE item.id = :taskId AND item.learning_plan_id = :planId
                          AND item.status IN ('PENDING', 'IN_PROGRESS')
                        """).param("taskId", taskId.toString()).param("planId", planId.toString())
                .query((rs, row) -> new PlanningDtos.PracticeTask(
                        uuid(rs.getString("id")), uuid(rs.getString("learning_plan_id")),
                        uuid(rs.getString("course_id")), uuid(rs.getString("question_id")),
                        rs.getString("question_title"), rs.getString("prompt_text"),
                        rs.getString("answer_type"), rs.getString("options_json"),
                        uuid(rs.getString("skill_id")), rs.getString("skill_name"),
                        rs.getInt("target_count"), rs.getInt("completed_count")))
                .optional().orElseThrow(() -> notFound("The plan task is not available for practice."));
        jdbc.sql("""
                        UPDATE learning_plan_item SET status = 'IN_PROGRESS',
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6))
                        WHERE id = :taskId AND status = 'PENDING'
                        """).param("taskId", taskId.toString()).update();
        return task;
    }

    @Transactional
    public LearningPlan regenerate(UUID courseId, UUID studentId, String reason) {
        UUID previousId = currentPlanId(courseId, studentId, true);
        PlanSource previous = jdbc.sql("""
                        SELECT * FROM learning_plan WHERE id = :planId FOR UPDATE
                        """).param("planId", previousId.toString())
                .query((rs, row) -> new PlanSource(
                        uuid(rs.getString("source_snapshot_id")), uuid(rs.getString("source_job_id")),
                        rs.getLong("plan_version"), rs.getString("rule_version"),
                        rs.getString("data_versions"), rs.getString("model_versions")))
                .single();
        Instant now = Instant.now();
        jdbc.sql("""
                        UPDATE learning_plan SET status = 'SUPERSEDED', superseded_at = :now
                        WHERE id = :planId AND status IN ('ACTIVE', 'COMPLETED', 'EXPIRED')
                        """).param("now", Timestamp.from(now)).param("planId", previousId.toString()).update();
        UUID newId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO learning_plan(
                            id, course_id, student_id, plan_version, source_snapshot_id,
                            source_job_id, rule_version, status, valid_until,
                            data_versions, model_versions, regeneration_reason)
                        VALUES (:id, :courseId, :studentId, :version, :snapshotId,
                            :jobId, :ruleVersion, 'ACTIVE', :validUntil,
                            :dataVersions, :modelVersions, :reason)
                        """).param("id", newId.toString()).param("courseId", courseId.toString())
                .param("studentId", studentId.toString()).param("version", previous.version() + 1)
                .param("snapshotId", previous.snapshotId().toString()).param("jobId", previous.jobId().toString())
                .param("ruleVersion", previous.ruleVersion()).param("validUntil", Timestamp.from(now.plus(Duration.ofDays(7))))
                .param("dataVersions", previous.dataVersions()).param("modelVersions", previous.modelVersions())
                .param("reason", reason).update();
        List<PlanItemSource> items = jdbc.sql("""
                        SELECT * FROM learning_plan_item WHERE learning_plan_id = :planId ORDER BY ordinal
                        """).param("planId", previousId.toString())
                .query((rs, row) -> new PlanItemSource(
                        rs.getInt("ordinal"), uuid(rs.getString("question_id")), uuid(rs.getString("skill_id")),
                        rs.getString("task_type"), rs.getString("title"), rs.getString("rationale_code"),
                        rs.getBigDecimal("target_mastery"), rs.getInt("target_count")))
                .list();
        for (PlanItemSource item : items) {
            jdbc.sql("""
                            INSERT INTO learning_plan_item(
                                id, learning_plan_id, ordinal, question_id, skill_id, task_type,
                                title, rationale_code, target_mastery, target_count,
                                completed_count, status, due_at)
                            VALUES (:id, :planId, :ordinal, :questionId, :skillId, :taskType,
                                :title, :reasonCode, :targetMastery, :targetCount,
                                0, 'PENDING', :dueAt)
                            """).param("id", UUID.randomUUID().toString()).param("planId", newId.toString())
                    .param("ordinal", item.ordinal()).param("questionId", item.questionId().toString())
                    .param("skillId", item.skillId().toString()).param("taskType", item.taskType())
                    .param("title", item.title()).param("reasonCode", item.reasonCode())
                    .param("targetMastery", item.targetMastery()).param("targetCount", item.targetCount())
                    .param("dueAt", Timestamp.from(now.plus(Duration.ofDays(item.ordinal())))).update();
        }
        jdbc.sql("""
                        UPDATE learning_plan_current_pointer SET learning_plan_id = :newId
                        WHERE course_id = :courseId AND student_id = :studentId
                        """).param("newId", newId.toString()).param("courseId", courseId.toString())
                .param("studentId", studentId.toString()).update();
        return readService.planById(newId);
    }

    private PlanningDtos.PlanLifecycle lifecycle(UUID planId) {
        PlanLifecycleRow row = jdbc.sql("""
                        SELECT id, course_id, student_id, plan_version, status,
                               valid_until, expired_at FROM learning_plan WHERE id = :planId
                        """).param("planId", planId.toString())
                .query((rs, ignored) -> new PlanLifecycleRow(
                        uuid(rs.getString("id")), uuid(rs.getString("course_id")),
                        uuid(rs.getString("student_id")), rs.getLong("plan_version"),
                        rs.getString("status"), offset(rs.getTimestamp("valid_until")),
                        offset(rs.getTimestamp("expired_at"))))
                .single();
        List<PlanningDtos.PlanTaskProjection> tasks = jdbc.sql("""
                        SELECT item.*, q.prompt_text, skill.name skill_name
                        FROM learning_plan_item item
                        JOIN question q ON q.id = item.question_id
                        JOIN knowledge_skill skill ON skill.id = item.skill_id
                        WHERE item.learning_plan_id = :planId ORDER BY item.ordinal
                        """).param("planId", planId.toString())
                .query((rs, ignored) -> new PlanningDtos.PlanTaskProjection(
                        uuid(rs.getString("id")), uuid(rs.getString("learning_plan_id")),
                        uuid(rs.getString("question_id")), rs.getString("title"),
                        uuid(rs.getString("skill_id")), rs.getString("skill_name"),
                        rs.getString("task_type"), rs.getString("rationale_code"),
                        rs.getInt("target_count"), rs.getInt("completed_count"), rs.getString("status"),
                        offset(rs.getTimestamp("due_at")), offset(rs.getTimestamp("started_at")),
                        offset(rs.getTimestamp("completed_at")), offset(rs.getTimestamp("skipped_at")),
                        rs.getString("skip_reason"),
                        "/api/v1/courses/" + row.courseId() + "/practice/tasks/" + rs.getString("id")))
                .list();
        return new PlanningDtos.PlanLifecycle(
                row.planId(), row.courseId(), row.studentId(), row.version(), row.status(),
                row.validUntil(), row.expiredAt(), tasks);
    }

    private UUID requireExpectedCurrent(UUID courseId, UUID studentId, UUID expectedPlanId) {
        if (expectedPlanId == null) {
            throw badRequest("PLAN_EXPECTED_ID_REQUIRED", "The expected current plan id is required.");
        }
        UUID current = currentPlanId(courseId, studentId, true);
        if (!current.equals(expectedPlanId)) {
            throw conflict("PLAN_CURRENT_CHANGED", "The current learning plan changed after it was read.");
        }
        return current;
    }

    private UUID currentPlanId(UUID courseId, UUID studentId, boolean lock) {
        return jdbc.sql("""
                        SELECT learning_plan_id FROM learning_plan_current_pointer
                        WHERE course_id = :courseId AND student_id = :studentId
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query((rs, row) -> uuid(rs.getString("learning_plan_id")))
                .optional().orElseThrow(() -> notFound("No current learning plan exists."));
    }

    private void requireActive(UUID planId) {
        expireIfNeeded(planId);
        String status = jdbc.sql("SELECT status FROM learning_plan WHERE id = :planId")
                .param("planId", planId.toString()).query(String.class).single();
        if (!"ACTIVE".equals(status)) {
            throw conflict("PLAN_NOT_ACTIVE", "The learning plan is no longer active.");
        }
    }

    private void expireIfNeeded(UUID planId) {
        jdbc.sql("""
                        UPDATE learning_plan SET status = 'EXPIRED', expired_at = CURRENT_TIMESTAMP(6)
                        WHERE id = :planId AND status = 'ACTIVE' AND valid_until < CURRENT_TIMESTAMP(6)
                        """).param("planId", planId.toString()).update();
    }

    private boolean taskHasStatus(UUID taskId, UUID planId, String status) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM learning_plan_item
                            WHERE id = :taskId AND learning_plan_id = :planId AND status = :status)
                        """).param("taskId", taskId.toString()).param("planId", planId.toString())
                .param("status", status).query(Boolean.class).single();
    }

    private void completePlanWhenTerminal(UUID planId) {
        jdbc.sql("""
                        UPDATE learning_plan plan SET status = 'COMPLETED'
                        WHERE plan.id = :planId AND plan.status = 'ACTIVE'
                          AND NOT EXISTS(SELECT 1 FROM learning_plan_item item
                              WHERE item.learning_plan_id = plan.id
                                AND item.status IN ('PENDING', 'IN_PROGRESS'))
                        """).param("planId", planId.toString()).update();
    }

    private static void requireSelf(EduTwinPrincipal student, UUID studentId) {
        if (student == null || !student.enabled() || !student.userId().equals(studentId)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "PLAN_STUDENT_SCOPE_DENIED",
                    "A student may update only the current personal plan.");
        }
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw badRequest("PLAN_SKIP_REASON_INVALID", "A valid skip reason is required.");
        }
        return value.trim();
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }
    private static DomainException conflict(String code, String detail) {
        return new DomainException(HttpStatus.CONFLICT, code, detail);
    }
    private static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, "LEARNING_PLAN_NOT_FOUND", detail);
    }

    private record PlanLifecycleRow(
            UUID planId, UUID courseId, UUID studentId, long version, String status,
            OffsetDateTime validUntil, OffsetDateTime expiredAt) {}
    private record PlanSource(
            UUID snapshotId, UUID jobId, long version, String ruleVersion,
            String dataVersions, String modelVersions) {}
    private record PlanItemSource(
            int ordinal, UUID questionId, UUID skillId, String taskType, String title,
            String reasonCode, java.math.BigDecimal targetMastery, int targetCount) {}
}
