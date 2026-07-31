package com.edutwin.risk;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskInterventionService {
    private static final Set<String> CASE_STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> ACTION_STATUSES = Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    private static final String CASE_SELECT = """
            SELECT rc.*, student.display_name student_name, course.title course_title,
                   assignee.display_name assignee_name
            FROM risk_case rc
            JOIN user_account student ON student.id = rc.student_id
            JOIN course ON course.id = rc.course_id
            LEFT JOIN user_account assignee ON assignee.id = rc.assigned_to
            """;

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public RiskInterventionService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${edutwin.risk.case-scan-delay:PT30S}")
    @Transactional
    public void createCasesForCurrentHighRisk() {
        List<AutoCase> candidates = jdbc.sql("""
                        SELECT tcp.course_id, tcp.student_id, rp.id prediction_id
                        FROM twin_current_pointer tcp
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                        WHERE rp.risk_band = 'HIGH'
                          AND NOT EXISTS (
                              SELECT 1 FROM risk_case rc
                              WHERE rc.course_id = tcp.course_id
                                AND rc.student_id = tcp.student_id
                                AND rc.status IN ('OPEN', 'IN_PROGRESS'))
                        """)
                .query((rs, row) -> new AutoCase(
                        uuid(rs.getString("course_id")),
                        uuid(rs.getString("student_id")),
                        uuid(rs.getString("prediction_id"))))
                .list();
        for (AutoCase candidate : candidates) {
            UUID caseId = UUID.randomUUID();
            try {
                jdbc.sql("""
                                INSERT INTO risk_case(
                                    id, course_id, student_id, source_risk_prediction_id,
                                    trigger_type, risk_band, title, summary, status, priority)
                                VALUES (:id, :courseId, :studentId, :predictionId,
                                    'AUTO_HIGH', 'HIGH', '高风险学情干预跟进',
                                    '当前学习孪生快照显示为高风险状态，需及时开展干预与跟进。',
                                    'OPEN', 'HIGH')
                                """)
                        .param("id", caseId.toString())
                        .param("courseId", candidate.courseId().toString())
                        .param("studentId", candidate.studentId().toString())
                        .param("predictionId", candidate.predictionId().toString())
                        .update();
                publish("RISK_CASE_AUTO_CREATED", null, caseId, candidate.studentId(),
                        Map.of("courseId", candidate.courseId().toString(), "riskBand", "HIGH"));
            } catch (DataIntegrityViolationException ignored) {
                // The generated active key makes concurrent scans idempotent.
            }
        }
    }

    public RiskDtos.CasePage cases(
            EduTwinPrincipal actor, String status, UUID assignedTo, int page, int size) {
        requireManager(actor);
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("RISK_CASE_PAGE_INVALID", "Page and size are outside the supported range.");
        }
        String visibility = visibilitySql(actor);
        String filters = "";
        if (status != null && !status.isBlank()) {
            String normalized = normalizedStatus(status, CASE_STATUSES, "RISK_CASE_STATUS_INVALID");
            status = normalized;
            filters += " AND rc.status = :status";
        }
        if (assignedTo != null) filters += " AND rc.assigned_to = :assignedTo";
        JdbcClient.StatementSpec countSpec = bindVisibility(
                jdbc.sql("SELECT COUNT(*) FROM risk_case rc JOIN student_profile sp ON sp.user_id = rc.student_id WHERE "
                        + visibility + filters), actor);
        JdbcClient.StatementSpec itemSpec = bindVisibility(jdbc.sql(
                CASE_SELECT + " JOIN student_profile sp ON sp.user_id = rc.student_id WHERE " + visibility + filters
                        + " ORDER BY FIELD(rc.priority, 'URGENT', 'HIGH', 'MEDIUM'), rc.updated_at DESC"
                        + " LIMIT :limit OFFSET :offset"), actor);
        if (status != null && !status.isBlank()) {
            countSpec = countSpec.param("status", status);
            itemSpec = itemSpec.param("status", status);
        }
        if (assignedTo != null) {
            countSpec = countSpec.param("assignedTo", assignedTo.toString());
            itemSpec = itemSpec.param("assignedTo", assignedTo.toString());
        }
        int total = countSpec.query(Integer.class).single();
        List<RiskDtos.CaseSummary> items = itemSpec.param("limit", size).param("offset", page * size)
                .query((rs, row) -> caseSummary(rs)).list();
        return new RiskDtos.CasePage(items, total, page, size);
    }

    public RiskDtos.CaseDetail detail(EduTwinPrincipal actor, UUID caseId) {
        RiskDtos.CaseSummary riskCase = managedCase(actor, caseId);
        List<RiskDtos.Assignment> assignments = jdbc.sql("""
                        SELECT * FROM risk_case_assignment_history
                        WHERE risk_case_id = :caseId ORDER BY created_at, id
                        """).param("caseId", caseId.toString())
                .query((rs, row) -> new RiskDtos.Assignment(
                        uuid(rs.getString("id")), nullableUuid(rs.getString("from_assignee")),
                        uuid(rs.getString("to_assignee")), uuid(rs.getString("changed_by")),
                        rs.getString("reason"), offset(rs.getTimestamp("created_at"))))
                .list();
        List<RiskDtos.InternalNote> notes = jdbc.sql("""
                        SELECT n.*, u.display_name author_name FROM risk_case_note n
                        JOIN user_account u ON u.id = n.author_id
                        WHERE n.risk_case_id = :caseId ORDER BY n.created_at, n.id
                        """).param("caseId", caseId.toString())
                .query((rs, row) -> new RiskDtos.InternalNote(
                        uuid(rs.getString("id")), uuid(rs.getString("author_id")),
                        rs.getString("author_name"), rs.getString("body"),
                        offset(rs.getTimestamp("created_at"))))
                .list();
        return new RiskDtos.CaseDetail(riskCase, assignments, notes, actions(caseId));
    }

    @Transactional
    public RiskDtos.CaseDetail createManualCase(
            EduTwinPrincipal actor, RiskDtos.CreateCaseRequest request) {
        requireManager(actor);
        if (request == null || request.courseId() == null || request.studentId() == null) {
            throw badRequest("RISK_CASE_REQUEST_INVALID", "Course and student are required.");
        }
        requireCanManage(actor, request.courseId(), request.studentId());
        String riskBand = currentRiskBand(request.courseId(), request.studentId());
        if (!"MEDIUM".equals(riskBand)) {
            throw conflict("RISK_CASE_MANUAL_BAND_INVALID", "Manual cases may be opened only for current medium risk.");
        }
        UUID predictionId = currentPredictionId(request.courseId(), request.studentId());
        UUID caseId = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO risk_case(
                                id, course_id, student_id, source_risk_prediction_id,
                                trigger_type, risk_band, title, summary, status, priority, created_by)
                            VALUES (:id, :courseId, :studentId, :predictionId,
                                'MANUAL_MEDIUM', 'MEDIUM', :title, :summary, 'OPEN', 'MEDIUM', :actorId)
                            """)
                    .param("id", caseId.toString())
                    .param("courseId", request.courseId().toString())
                    .param("studentId", request.studentId().toString())
                    .param("predictionId", predictionId.toString())
                    .param("title", required(request.title(), 200, "title"))
                    .param("summary", required(request.summary(), 1000, "summary"))
                    .param("actorId", actor.userId().toString())
                    .update();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("RISK_CASE_ALREADY_ACTIVE", "An active case already exists for the student and course.");
        }
        publish("RISK_CASE_CREATED", actor.userId(), caseId, request.studentId(),
                Map.of("courseId", request.courseId().toString(), "riskBand", "MEDIUM"));
        return detail(actor, caseId);
    }

    @Transactional
    public RiskDtos.CaseDetail assign(
            EduTwinPrincipal actor, UUID caseId, RiskDtos.AssignmentRequest request) {
        RiskDtos.CaseSummary riskCase = managedCase(actor, caseId);
        if (request == null || request.assigneeId() == null) {
            throw badRequest("RISK_CASE_ASSIGNMENT_INVALID", "An assignee is required.");
        }
        requireExpectedVersion(riskCase, request.expectedVersion());
        requireEligibleAssignee(request.assigneeId(), riskCase.courseId(), riskCase.studentId());
        String reason = required(request.reason(), 300, "assignment reason");
        int updated = jdbc.sql("""
                        UPDATE risk_case SET assigned_to = :assigneeId, version = version + 1
                        WHERE id = :caseId AND version = :version AND status <> 'CLOSED'
                        """).param("assigneeId", request.assigneeId().toString())
                .param("caseId", caseId.toString()).param("version", riskCase.version()).update();
        requireUpdated(updated);
        jdbc.sql("""
                        INSERT INTO risk_case_assignment_history(
                            id, risk_case_id, from_assignee, to_assignee, changed_by, reason)
                        VALUES (:id, :caseId, :fromAssignee, :toAssignee, :actorId, :reason)
                        """).param("id", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                .param("fromAssignee", riskCase.assignedTo() == null ? null : riskCase.assignedTo().toString())
                .param("toAssignee", request.assigneeId().toString())
                .param("actorId", actor.userId().toString()).param("reason", reason).update();
        publish("RISK_CASE_ASSIGNED", actor.userId(), caseId, request.assigneeId(),
                Map.of("reason", reason));
        return detail(actor, caseId);
    }

    @Transactional
    public RiskDtos.CaseDetail transition(
            EduTwinPrincipal actor, UUID caseId, RiskDtos.StatusRequest request) {
        RiskDtos.CaseSummary riskCase = managedCase(actor, caseId);
        if (request == null) throw badRequest("RISK_CASE_STATUS_INVALID", "A target status is required.");
        requireExpectedVersion(riskCase, request.expectedVersion());
        String target = normalizedStatus(request.targetStatus(), CASE_STATUSES, "RISK_CASE_STATUS_INVALID");
        if (!allowedTransition(riskCase.status(), target)) {
            throw conflict("RISK_CASE_TRANSITION_INVALID", "The requested case status transition is not allowed.");
        }
        String reason = required(request.reason(), 300, "status reason");
        int updated = jdbc.sql("""
                        UPDATE risk_case
                        SET status = :target, version = version + 1,
                            resolved_at = CASE WHEN :target = 'RESOLVED' THEN CURRENT_TIMESTAMP(6)
                                               WHEN :target = 'IN_PROGRESS' THEN NULL ELSE resolved_at END,
                            closed_at = CASE WHEN :target = 'CLOSED' THEN CURRENT_TIMESTAMP(6) ELSE NULL END
                        WHERE id = :caseId AND version = :version
                        """).param("target", target).param("caseId", caseId.toString())
                .param("version", riskCase.version()).update();
        requireUpdated(updated);
        publish("RISK_CASE_STATUS_CHANGED", actor.userId(), caseId, riskCase.studentId(),
                Map.of("from", riskCase.status(), "to", target, "reason", reason));
        return detail(actor, caseId);
    }

    @Transactional
    public RiskDtos.CaseDetail addNote(
            EduTwinPrincipal actor, UUID caseId, RiskDtos.NoteRequest request) {
        managedCase(actor, caseId);
        String body = required(request == null ? null : request.body(), 2000, "note body");
        UUID noteId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO risk_case_note(id, risk_case_id, author_id, body)
                        VALUES (:id, :caseId, :actorId, :body)
                        """).param("id", noteId.toString()).param("caseId", caseId.toString())
                .param("actorId", actor.userId().toString()).param("body", body).update();
        publish("RISK_CASE_NOTE_ADDED", actor.userId(), caseId, noteId, Map.of());
        return detail(actor, caseId);
    }

    @Transactional
    public RiskDtos.CaseDetail addAction(
            EduTwinPrincipal actor, UUID caseId, RiskDtos.ActionRequest request) {
        RiskDtos.CaseSummary riskCase = managedCase(actor, caseId);
        if ("CLOSED".equals(riskCase.status())) {
            throw conflict("RISK_CASE_CLOSED", "Actions cannot be added to a closed case.");
        }
        UUID actionId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO risk_action_item(
                            id, risk_case_id, student_id, title, description, status, due_at, created_by)
                        VALUES (:id, :caseId, :studentId, :title, :description, 'PENDING', :dueAt, :actorId)
                        """).param("id", actionId.toString()).param("caseId", caseId.toString())
                .param("studentId", riskCase.studentId().toString())
                .param("title", required(request == null ? null : request.title(), 200, "action title"))
                .param("description", required(request == null ? null : request.description(), 1000, "action description"))
                .param("dueAt", request == null || request.dueAt() == null ? null : Timestamp.from(request.dueAt().toInstant()))
                .param("actorId", actor.userId().toString()).update();
        publish("RISK_ACTION_CREATED", actor.userId(), caseId, actionId,
                Map.of("studentId", riskCase.studentId().toString()));
        return detail(actor, caseId);
    }

    @Transactional
    public RiskDtos.CaseDetail updateAction(
            EduTwinPrincipal actor, UUID caseId, UUID actionId, RiskDtos.ActionStatusRequest request) {
        managedCase(actor, caseId);
        String target = normalizedStatus(
                request == null ? null : request.targetStatus(), ACTION_STATUSES, "RISK_ACTION_STATUS_INVALID");
        int updated = jdbc.sql("""
                        UPDATE risk_action_item SET status = :target,
                            started_at = CASE WHEN :target = 'IN_PROGRESS' AND started_at IS NULL
                                              THEN CURRENT_TIMESTAMP(6) ELSE started_at END,
                            completed_at = CASE WHEN :target = 'COMPLETED'
                                                THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                            result_summary = :result
                        WHERE id = :actionId AND risk_case_id = :caseId
                        """).param("target", target)
                .param("result", optional(request.resultSummary(), 1000, "result summary"))
                .param("actionId", actionId.toString()).param("caseId", caseId.toString()).update();
        if (updated != 1) throw notFound("Risk action item was not found.");
        publish("RISK_ACTION_STATUS_CHANGED", actor.userId(), caseId, actionId, Map.of("to", target));
        return detail(actor, caseId);
    }

    public List<RiskDtos.StudentActionProjection> studentActions(EduTwinPrincipal student) {
        requireStudent(student);
        return jdbc.sql("""
                        SELECT action.*, rc.course_id, course.title course_title
                        FROM risk_action_item action
                        JOIN risk_case rc ON rc.id = action.risk_case_id
                        JOIN course ON course.id = rc.course_id
                        WHERE action.student_id = :studentId
                          AND action.status <> 'CANCELLED'
                        ORDER BY action.status = 'COMPLETED', action.due_at, action.created_at
                        """).param("studentId", student.userId().toString())
                .query((rs, row) -> new RiskDtos.StudentActionProjection(
                        uuid(rs.getString("id")), uuid(rs.getString("risk_case_id")),
                        uuid(rs.getString("course_id")), rs.getString("course_title"),
                        rs.getString("title"), rs.getString("description"), rs.getString("status"),
                        offset(rs.getTimestamp("due_at")), rs.getString("result_summary"),
                        feedback(uuid(rs.getString("id")))))
                .list();
    }

    @Transactional
    public RiskDtos.StudentActionProjection addFeedback(
            EduTwinPrincipal student, UUID actionId, RiskDtos.FeedbackRequest request) {
        requireStudent(student);
        ActionOwner owner = jdbc.sql("""
                        SELECT a.risk_case_id, rc.course_id FROM risk_action_item a
                        JOIN risk_case rc ON rc.id = a.risk_case_id
                        WHERE a.id = :actionId AND a.student_id = :studentId
                          AND a.status NOT IN ('COMPLETED', 'CANCELLED')
                        """).param("actionId", actionId.toString()).param("studentId", student.userId().toString())
                .query((rs, row) -> new ActionOwner(
                        uuid(rs.getString("risk_case_id")), uuid(rs.getString("course_id"))))
                .optional().orElseThrow(() -> notFound("An actionable student projection was not found."));
        String body = required(request == null ? null : request.body(), 2000, "feedback body");
        UUID feedbackId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO risk_action_feedback(id, action_item_id, student_id, body)
                        VALUES (:id, :actionId, :studentId, :body)
                        """).param("id", feedbackId.toString()).param("actionId", actionId.toString())
                .param("studentId", student.userId().toString()).param("body", body).update();
        jdbc.sql("""
                        UPDATE risk_action_item SET status = 'IN_PROGRESS',
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6))
                        WHERE id = :actionId AND status = 'PENDING'
                        """).param("actionId", actionId.toString()).update();
        publish("RISK_ACTION_FEEDBACK_ADDED", student.userId(), owner.caseId(), feedbackId,
                Map.of("actionId", actionId.toString()));
        return studentActions(student).stream()
                .filter(item -> item.actionItemId().equals(actionId)).findFirst()
                .orElseThrow(() -> notFound("Student action item was not found."));
    }

    private RiskDtos.CaseSummary managedCase(EduTwinPrincipal actor, UUID caseId) {
        requireManager(actor);
        RiskDtos.CaseSummary riskCase = jdbc.sql(CASE_SELECT + " WHERE rc.id = :caseId")
                .param("caseId", caseId.toString()).query((rs, row) -> caseSummary(rs))
                .optional().orElseThrow(() -> notFound("Risk case was not found."));
        requireCanManage(actor, riskCase.courseId(), riskCase.studentId());
        return riskCase;
    }

    private List<RiskDtos.ActionItem> actions(UUID caseId) {
        return jdbc.sql("""
                        SELECT * FROM risk_action_item WHERE risk_case_id = :caseId
                        ORDER BY created_at, id
                        """).param("caseId", caseId.toString())
                .query((rs, row) -> {
                    UUID actionId = uuid(rs.getString("id"));
                    return new RiskDtos.ActionItem(
                            actionId, uuid(rs.getString("risk_case_id")), uuid(rs.getString("student_id")),
                            rs.getString("title"), rs.getString("description"), rs.getString("status"),
                            offset(rs.getTimestamp("due_at")), rs.getString("result_summary"),
                            offset(rs.getTimestamp("created_at")), offset(rs.getTimestamp("started_at")),
                            offset(rs.getTimestamp("completed_at")), feedback(actionId));
                }).list();
    }

    private List<RiskDtos.Feedback> feedback(UUID actionId) {
        return jdbc.sql("""
                        SELECT * FROM risk_action_feedback WHERE action_item_id = :actionId
                        ORDER BY created_at, id
                        """).param("actionId", actionId.toString())
                .query((rs, row) -> new RiskDtos.Feedback(
                        uuid(rs.getString("id")), uuid(rs.getString("student_id")),
                        rs.getString("body"), offset(rs.getTimestamp("created_at"))))
                .list();
    }

    private void requireCanManage(EduTwinPrincipal actor, UUID courseId, UUID studentId) {
        boolean allowed = actor.role() == UserRole.TEACHER
                ? jdbc.sql("""
                                SELECT EXISTS(SELECT 1 FROM teaching_assignment
                                    WHERE course_id = :courseId AND teacher_id = :actorId)
                                """).param("courseId", courseId.toString())
                        .param("actorId", actor.userId().toString()).query(Boolean.class).single()
                : jdbc.sql("""
                                SELECT EXISTS(
                                    SELECT 1 FROM counselor_scope cs
                                    JOIN student_profile sp ON sp.user_id = :studentId
                                    WHERE cs.counselor_id = :actorId
                                      AND cs.college = sp.college AND cs.major = sp.major
                                      AND cs.cohort_year = sp.cohort_year
                                      AND (cs.scope_type = 'COHORT'
                                           OR (cs.scope_type = 'CLASS' AND cs.class_name = sp.class_name)))
                                """).param("studentId", studentId.toString())
                        .param("actorId", actor.userId().toString()).query(Boolean.class).single();
        if (!allowed) throw forbidden("The risk case is outside the active role scope.");
    }

    private void requireEligibleAssignee(UUID assigneeId, UUID courseId, UUID studentId) {
        boolean eligible = jdbc.sql("""
                        SELECT (
                            EXISTS(
                                SELECT 1 FROM user_role ur
                                JOIN teaching_assignment ta ON ta.teacher_id = ur.user_id
                                WHERE ur.user_id = :assigneeId AND ur.role_code = 'TEACHER'
                                  AND ta.course_id = :courseId)
                            OR EXISTS(
                                SELECT 1 FROM user_role ur
                                JOIN counselor_scope cs ON cs.counselor_id = ur.user_id
                                JOIN student_profile sp ON sp.user_id = :studentId
                                WHERE ur.user_id = :assigneeId AND ur.role_code = 'COUNSELOR'
                                  AND cs.college = sp.college AND cs.major = sp.major
                                  AND cs.cohort_year = sp.cohort_year
                                  AND (cs.scope_type = 'COHORT'
                                       OR (cs.scope_type = 'CLASS' AND cs.class_name = sp.class_name)))
                        )
                        """).param("assigneeId", assigneeId.toString())
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query(Boolean.class).single();
        if (!eligible) throw forbidden("The assignee cannot manage the case scope.");
    }

    private String currentRiskBand(UUID courseId, UUID studentId) {
        return jdbc.sql("""
                        SELECT rp.risk_band FROM twin_current_pointer tcp
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                        WHERE tcp.course_id = :courseId AND tcp.student_id = :studentId
                        """).param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query(String.class).optional().orElseThrow(() -> notFound("Current risk prediction was not found."));
    }

    private UUID currentPredictionId(UUID courseId, UUID studentId) {
        return jdbc.sql("""
                        SELECT rp.id FROM twin_current_pointer tcp
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                        WHERE tcp.course_id = :courseId AND tcp.student_id = :studentId
                        """).param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query((rs, row) -> uuid(rs.getString("id"))).single();
    }

    private String visibilitySql(EduTwinPrincipal actor) {
        if (actor.role() == UserRole.TEACHER) {
            return "EXISTS(SELECT 1 FROM teaching_assignment ta WHERE ta.course_id = rc.course_id AND ta.teacher_id = :actorId)";
        }
        return """
                EXISTS(SELECT 1 FROM counselor_scope cs
                    WHERE cs.counselor_id = :actorId
                      AND cs.college = sp.college AND cs.major = sp.major
                      AND cs.cohort_year = sp.cohort_year
                      AND (cs.scope_type = 'COHORT'
                           OR (cs.scope_type = 'CLASS' AND cs.class_name = sp.class_name)))
                """;
    }

    private static JdbcClient.StatementSpec bindVisibility(
            JdbcClient.StatementSpec spec, EduTwinPrincipal actor) {
        return spec.param("actorId", actor.userId().toString());
    }

    private static RiskDtos.CaseSummary caseSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskDtos.CaseSummary(
                uuid(rs.getString("id")), uuid(rs.getString("course_id")), uuid(rs.getString("student_id")),
                rs.getString("student_name"), rs.getString("course_title"), rs.getString("trigger_type"),
                rs.getString("risk_band"), rs.getString("title"), rs.getString("summary"),
                rs.getString("status"), rs.getString("priority"), nullableUuid(rs.getString("assigned_to")),
                rs.getString("assignee_name"), rs.getLong("version"), offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")), offset(rs.getTimestamp("resolved_at")),
                offset(rs.getTimestamp("closed_at")));
    }

    private static boolean allowedTransition(String from, String to) {
        if (from.equals(to)) return false;
        return switch (from) {
            case "OPEN" -> Set.of("IN_PROGRESS", "CLOSED").contains(to);
            case "IN_PROGRESS" -> Set.of("RESOLVED", "CLOSED").contains(to);
            case "RESOLVED" -> Set.of("IN_PROGRESS", "CLOSED").contains(to);
            default -> false;
        };
    }

    private static void requireExpectedVersion(RiskDtos.CaseSummary riskCase, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != riskCase.version()) {
            throw conflict("RISK_CASE_VERSION_CONFLICT", "The case changed after it was read.");
        }
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) throw conflict("RISK_CASE_VERSION_CONFLICT", "The case changed concurrently.");
    }

    private static String normalizedStatus(String value, Set<String> allowed, String code) {
        if (value == null || !allowed.contains(value.trim().toUpperCase())) {
            throw badRequest(code, "The requested status is invalid.");
        }
        return value.trim().toUpperCase();
    }

    private static String required(String value, int maxLength, String label) {
        String normalized = optional(value, maxLength, label);
        if (normalized == null || normalized.isBlank()) {
            throw badRequest("RISK_CASE_TEXT_INVALID", "A non-empty " + label + " is required.");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String label) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest("RISK_CASE_TEXT_INVALID", "The " + label + " exceeds " + maxLength + " characters.");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static void requireManager(EduTwinPrincipal actor) {
        if (actor == null || !actor.enabled()
                || (actor.role() != UserRole.TEACHER && actor.role() != UserRole.COUNSELOR)) {
            throw forbidden("An enabled teacher or counselor role is required.");
        }
    }

    private static void requireStudent(EduTwinPrincipal actor) {
        if (actor == null || !actor.enabled() || actor.role() != UserRole.STUDENT) {
            throw forbidden("An enabled student role is required.");
        }
    }

    private void publish(String action, UUID actorId, UUID caseId, UUID targetId, Map<String, String> attributes) {
        events.publishEvent(new RiskBusinessEvent(action, actorId, caseId, targetId, attributes, Instant.now()));
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static UUID nullableUuid(String value) { return value == null ? null : uuid(value); }
    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }
    private static DomainException conflict(String code, String detail) {
        return new DomainException(HttpStatus.CONFLICT, code, detail);
    }
    private static DomainException forbidden(String detail) {
        return new DomainException(HttpStatus.FORBIDDEN, "RISK_CASE_SCOPE_DENIED", detail);
    }
    private static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, "RISK_CASE_NOT_FOUND", detail);
    }

    private record AutoCase(UUID courseId, UUID studentId, UUID predictionId) {}
    private record ActionOwner(UUID caseId, UUID courseId) {}
}
