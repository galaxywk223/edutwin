package com.edutwin.admin;

import com.edutwin.admin.AdminDtos.AdminUser;
import com.edutwin.admin.AdminDtos.AuditEvent;
import com.edutwin.admin.AdminDtos.DatasetVersion;
import com.edutwin.admin.AdminDtos.ModelDeployment;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.notification.NotificationService;
import com.edutwin.shared.web.DomainException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private static final Set<String> ROLES = Set.of("ADMIN", "TEACHER", "STUDENT", "COUNSELOR");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final UserProvisioningImportService userImports;
    private final NotificationService notifications;
    private final BusinessAuditService businessAudit;

    public AdminService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            UserProvisioningImportService userImports, NotificationService notifications,
            BusinessAuditService businessAudit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.userImports = userImports;
        this.notifications = notifications;
        this.businessAudit = businessAudit;
    }

    public AdminDtos.Overview overview() {
        return new AdminDtos.Overview(
                count("SELECT COUNT(*) FROM user_account WHERE enabled = TRUE"),
                count("SELECT COUNT(*) FROM user_role WHERE role_code = 'TEACHER'"),
                count("SELECT COUNT(*) FROM user_role WHERE role_code = 'STUDENT'"),
                count("SELECT COUNT(*) FROM user_role WHERE role_code = 'COUNSELOR'"),
                count("SELECT COUNT(*) FROM dataset_version WHERE lifecycle_status = 'ENABLED'"),
                count("SELECT COUNT(*) FROM model_deployment"), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public AdminDtos.UserList users() {
        List<AdminUser> items = jdbc.sql("""
                        SELECT u.id, u.username, u.display_name, u.enabled, u.must_change_password,
                               u.origin_type, u.created_at, u.updated_at, u.last_active_role,
                               GROUP_CONCAT(ur.role_code ORDER BY ur.role_code) role_codes
                        FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                        GROUP BY u.id, u.username, u.display_name, u.enabled, u.must_change_password,
                                 u.origin_type, u.created_at, u.updated_at, u.last_active_role
                        ORDER BY u.created_at DESC, u.username
                        """)
                .query((rs, row) -> mapUser(rs)).list();
        return new AdminDtos.UserList(items, items.size());
    }

    @Transactional
    public AdminUser createUser(EduTwinPrincipal actor, AdminDtos.UserCreateRequest request) {
        String username = required(request.username(), "username", 100);
        String displayName = required(request.displayName(), "displayName", 120);
        Set<String> roles = roles(request.roles(), request.role());
        if (request.password() == null || request.password().length() < 12) {
            throw badRequest("ADMIN_PASSWORD_TOO_SHORT", "Account passwords require at least 12 characters.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO user_account(
                            id, username, password_hash, display_name, enabled, must_change_password, origin_type)
                        VALUES (:id, :username, :password, :displayName, TRUE, TRUE, 'MANUAL')
                        """)
                .param("id", id.toString()).param("username", username)
                .param("password", passwordEncoder.encode(request.password()))
                .param("displayName", displayName).update();
        for (String role : roles) {
            jdbc.sql("INSERT INTO user_role(user_id, role_code, granted_by) VALUES (:id, :role, :actor)")
                    .param("id", id.toString()).param("role", role)
                    .param("actor", actor.userId().toString()).update();
            roleHistory(id, role, "GRANTED", actor.userId(), "Account creation");
            createProfile(id, role);
        }
        String activeRole = preferredRole(roles);
        jdbc.sql("UPDATE user_account SET last_active_role = :role WHERE id = :id")
                .param("role", activeRole).param("id", id.toString()).update();
        jdbc.sql("INSERT INTO user_profile(user_id) VALUES (:id)").param("id", id.toString()).update();
        audit(actor, "USER_CREATED", "USER", id.toString(), null,
                Map.of("username", username, "displayName", displayName, "roles", roles));
        return user(id);
    }

    @Transactional
    public AdminUser updateUser(EduTwinPrincipal actor, UUID userId, AdminDtos.UserUpdateRequest request) {
        AdminUser before = user(userId);
        Set<String> nextRoles = request.roles() == null && request.role() == null
                ? new LinkedHashSet<>(before.roles()) : roles(request.roles(), request.role());
        boolean nextEnabled = request.enabled() == null ? before.enabled() : request.enabled();
        if (before.roles().contains("ADMIN") && (!nextRoles.contains("ADMIN") || !nextEnabled)) {
            lockActiveAdministrators();
            if (activeAdminCount() <= 1) {
                throw conflict("LAST_ADMIN_REQUIRED", "The last enabled administrator cannot be disabled or demoted.");
            }
        }
        replaceRoles(actor, userId, new LinkedHashSet<>(before.roles()), nextRoles);
        String name = request.displayName() == null ? before.displayName()
                : required(request.displayName(), "displayName", 120);
        jdbc.sql("""
                UPDATE user_account SET display_name = :name, enabled = :enabled,
                    token_version = token_version + 1,
                    last_active_role = CASE
                        WHEN last_active_role IN (SELECT role_code FROM user_role WHERE user_id = :id)
                        THEN last_active_role ELSE :fallbackRole END
                WHERE id = :id
                """)
                .param("name", name).param("enabled", nextEnabled)
                .param("fallbackRole", preferredRole(nextRoles))
                .param("id", userId.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = :fallbackRole WHERE id = :id AND last_active_role IS NULL")
                .param("fallbackRole", preferredRole(nextRoles)).param("id", userId.toString()).update();
        AdminUser after = user(userId);
        audit(actor, "USER_UPDATED", "USER", userId.toString(), before, after);
        notifications.create(userId, "ACCOUNT_CHANGED", "账号信息已更新", "账号状态或角色授权已更新。",
                "/profile", "account-change:" + userId + ":" + after.updatedAt(), null);
        return after;
    }

    @Transactional
    public AdminDtos.TemporaryPassword resetPassword(EduTwinPrincipal actor, UUID userId) {
        AdminUser before = user(userId);
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String temporary = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                UPDATE user_account SET password_hash = :password, must_change_password = TRUE,
                    token_version = token_version + 1 WHERE id = :id
                """)
                .param("password", passwordEncoder.encode(temporary)).param("id", userId.toString()).update();
        audit(actor, "PASSWORD_RESET", "USER", userId.toString(),
                Map.of("mustChangePassword", before.mustChangePassword()), Map.of("mustChangePassword", true));
        notifications.create(userId, "PASSWORD_RESET", "密码已重置", "管理员已重置账号密码，请使用临时密码登录并立即修改。",
                "/profile", "password-reset:" + userId + ":" + System.currentTimeMillis(), null);
        return new AdminDtos.TemporaryPassword(temporary, true);
    }

    public AdminDtos.StudentGroupList studentGroups() {
        List<AdminDtos.StudentGroup> items = jdbc.sql("""
                        SELECT 'COHORT' scope_type, college, major, cohort_year,
                               NULL class_name, COUNT(*) student_count
                        FROM student_profile
                        WHERE college IS NOT NULL AND major IS NOT NULL AND cohort_year IS NOT NULL
                        GROUP BY college, major, cohort_year
                        UNION ALL
                        SELECT 'CLASS' scope_type, college, major, cohort_year,
                               class_name, COUNT(*) student_count
                        FROM student_profile
                        WHERE college IS NOT NULL AND major IS NOT NULL
                          AND cohort_year IS NOT NULL AND class_name IS NOT NULL
                        GROUP BY college, major, cohort_year, class_name
                        ORDER BY cohort_year DESC, college, major, scope_type, class_name
                        """)
                .query((rs, row) -> new AdminDtos.StudentGroup(
                        rs.getString("scope_type"), rs.getString("college"), rs.getString("major"),
                        rs.getInt("cohort_year"), rs.getString("class_name"), rs.getInt("student_count")))
                .list();
        return new AdminDtos.StudentGroupList(items, items.size());
    }

    public UserProvisioningImportService.TemplateFile userImportTemplate(String template) {
        return userImports.template(template);
    }

    public AdminDtos.UserImportPreview previewUserImport(List<org.springframework.web.multipart.MultipartFile> files) {
        return userImports.preview(files);
    }

    public AdminDtos.UserImportResult commitUserImport(EduTwinPrincipal actor,
            List<org.springframework.web.multipart.MultipartFile> files, String expectedSha256) {
        AdminDtos.UserImportResult result = userImports.commit(actor, files, expectedSha256);
        audit(actor, "USERS_BULK_IMPORTED", "USER_IMPORT", result.fileSha256(), null,
                Map.of("createdCount", result.createdCount(), "updatedCount", result.updatedCount(),
                        "membershipCount", result.membershipCount()));
        return result;
    }

    public AdminDtos.CounselorScopeList counselorScopes(UUID counselorId) {
        requireRole(counselorId, "COUNSELOR");
        List<AdminDtos.CounselorScope> items = jdbc.sql("""
                        SELECT scope_type, college, major, cohort_year, class_name
                        FROM counselor_scope
                        WHERE counselor_id = :counselorId
                        ORDER BY cohort_year DESC, college, major, scope_type, class_name
                        """)
                .param("counselorId", counselorId.toString())
                .query((rs, row) -> new AdminDtos.CounselorScope(
                        rs.getString("scope_type"), rs.getString("college"), rs.getString("major"),
                        rs.getInt("cohort_year"), rs.getString("class_name")))
                .list();
        return new AdminDtos.CounselorScopeList(items, items.size());
    }

    @Transactional
    public AdminDtos.CounselorScopeList replaceCounselorScopes(
            EduTwinPrincipal actor, UUID counselorId, AdminDtos.CounselorScopeReplaceRequest request) {
        requireRole(counselorId, "COUNSELOR");
        if (request == null || request.items() == null || request.items().size() > 100) {
            throw badRequest("COUNSELOR_SCOPE_INVALID", "At most 100 counselor scopes are allowed.");
        }
        AdminDtos.CounselorScopeList before = counselorScopes(counselorId);
        jdbc.sql("DELETE FROM counselor_scope WHERE counselor_id = :counselorId")
                .param("counselorId", counselorId.toString()).update();
        for (AdminDtos.CounselorScope item : request.items()) {
            String type = required(item.scopeType(), "scopeType", 20).toUpperCase();
            if (!Set.of("COHORT", "CLASS").contains(type)
                    || item.cohortYear() < 2000 || item.cohortYear() > 2200) {
                throw badRequest("COUNSELOR_SCOPE_INVALID", "Counselor scope type or cohort year is invalid.");
            }
            String college = required(item.college(), "college", 160);
            String major = required(item.major(), "major", 160);
            String className = "CLASS".equals(type) ? required(item.className(), "className", 200) : null;
            String scopeKey = String.join("|", type, college, major,
                    Integer.toString(item.cohortYear()), className == null ? "*" : className);
            jdbc.sql("""
                            INSERT INTO counselor_scope(
                                counselor_id, scope_type, scope_key, college, major,
                                cohort_year, class_name, created_by)
                            VALUES (:counselorId, :scopeType, :scopeKey, :college, :major,
                                :cohortYear, :className, :createdBy)
                            """)
                    .param("counselorId", counselorId.toString()).param("scopeType", type)
                    .param("scopeKey", scopeKey).param("college", college).param("major", major)
                    .param("cohortYear", item.cohortYear()).param("className", className)
                    .param("createdBy", actor.userId().toString()).update();
        }
        AdminDtos.CounselorScopeList after = counselorScopes(counselorId);
        audit(actor, "COUNSELOR_SCOPES_REPLACED", "USER", counselorId.toString(), before, after);
        return after;
    }

    public AdminDtos.DatasetList datasets() {
        List<DatasetVersion> items = jdbc.sql("""
                        SELECT dv.version_id, ds.source_key, ds.name, dv.lifecycle_status,
                               dv.row_count, dv.manifest_sha256, dv.processed_at,
                               EXISTS(SELECT 1 FROM course c WHERE c.data_version = dv.version_id)
                               OR EXISTS(SELECT 1 FROM model_deployment md
                                   JOIN model_version mv ON mv.version_id = md.active_version_id
                                      OR mv.version_id = md.rollback_version_id
                                   WHERE mv.dataset_version_id = dv.version_id) referenced
                        FROM dataset_version dv JOIN dataset_source ds ON ds.id = dv.source_id
                        ORDER BY dv.processed_at DESC, dv.version_id
                        """)
                .query((rs, row) -> new DatasetVersion(rs.getString("version_id"), rs.getString("source_key"),
                        rs.getString("name"), rs.getString("lifecycle_status"), rs.getLong("row_count"),
                        rs.getString("manifest_sha256"), rs.getObject("processed_at", OffsetDateTime.class),
                        rs.getBoolean("referenced"))).list();
        return new AdminDtos.DatasetList(items, items.size());
    }

    public AdminDtos.DatasetImpact datasetImpact(String versionId) {
        dataset(versionId);
        Map<String, Object> counts = jdbc.sql("""
                        SELECT
                          (SELECT COUNT(*) FROM course WHERE data_version = :versionId) course_references,
                          (SELECT COUNT(*) FROM model_deployment md JOIN model_version mv
                            ON mv.version_id = md.active_version_id
                            WHERE mv.dataset_version_id = :versionId) active_model_references,
                          (SELECT COUNT(*) FROM model_deployment md JOIN model_version mv
                            ON mv.version_id = md.rollback_version_id
                            WHERE mv.dataset_version_id = :versionId) rollback_model_references
                        """)
                .param("versionId", versionId)
                .query((rs, row) -> Map.<String, Object>of(
                        "course", rs.getInt("course_references"),
                        "active", rs.getInt("active_model_references"),
                        "rollback", rs.getInt("rollback_model_references")))
                .single();
        int courses = (int) counts.get("course");
        int active = (int) counts.get("active");
        int rollback = (int) counts.get("rollback");
        List<String> blockers = new ArrayList<>();
        if (courses > 0) blockers.add("COURSE_REFERENCE");
        if (active > 0) blockers.add("ACTIVE_MODEL_REFERENCE");
        if (rollback > 0) blockers.add("ROLLBACK_MODEL_REFERENCE");
        return new AdminDtos.DatasetImpact(
                versionId, courses, active, rollback, true, blockers.isEmpty(), blockers);
    }

    @Transactional
    public DatasetVersion changeDatasetStatus(
            EduTwinPrincipal actor, String versionId, String requestedStatus, String requestedReason) {
        String status = required(requestedStatus, "status", 20).toUpperCase();
        if (!Set.of("ENABLED", "DEPRECATED", "RETIRED").contains(status)) {
            throw badRequest("DATASET_STATUS_INVALID",
                    "Dataset status must be ENABLED, DEPRECATED, or RETIRED.");
        }
        String reason = required(requestedReason, "reason", 500);
        DatasetVersion before = dataset(versionId);
        if (before.lifecycleStatus().equals(status)) return before;
        if ("RETIRED".equals(before.lifecycleStatus())) {
            throw conflict("DATASET_VERSION_RETIRED", "A retired dataset version cannot be reactivated.");
        }
        AdminDtos.DatasetImpact impact = datasetImpact(versionId);
        if ("RETIRED".equals(status) && !impact.canRetire()) {
            businessAudit.record(actor, "DATASET_STATUS_CHANGED", "DATASET_VERSION", versionId,
                    "FAILED", reason, "DATASET_VERSION_IN_USE", before, null, impact);
            throw conflict("DATASET_VERSION_IN_USE",
                    "A dataset version with course, active-model, or rollback-model references cannot be retired.");
        }
        jdbc.sql("UPDATE dataset_version SET lifecycle_status = :status WHERE version_id = :id")
                .param("status", status).param("id", versionId).update();
        DatasetVersion after = dataset(versionId);
        businessAudit.record(actor, "DATASET_STATUS_CHANGED", "DATASET_VERSION", versionId,
                "SUCCEEDED", reason, null, before, after, impact);
        redis.delete("transparency:current");
        return after;
    }

    public AdminDtos.ModelDeploymentList deployments() {
        List<ModelDeployment> items = jdbc.sql("""
                        SELECT task_name, active_version_id, rollback_version_id, deployed_at, deployed_by
                        FROM model_deployment ORDER BY task_name
                        """)
                .query((rs, row) -> new ModelDeployment(rs.getString("task_name"),
                        rs.getString("active_version_id"), rs.getString("rollback_version_id"),
                        rs.getObject("deployed_at", OffsetDateTime.class), rs.getString("deployed_by"))).list();
        return new AdminDtos.ModelDeploymentList(items, items.size());
    }

    @Transactional
    public AdminDtos.ModelRollbackResult rollbackDeployment(
            EduTwinPrincipal actor, String taskName, AdminDtos.ModelRollbackRequest request) {
        String task = required(taskName, "taskName", 50);
        String expected = required(request.expectedActiveVersionId(), "expectedActiveVersionId", 128);
        String target = required(request.targetVersionId(), "targetVersionId", 128);
        String reason = required(request.reason(), "reason", 500);
        ModelDeployment before = deployment(task);
        if (!before.activeVersionId().equals(expected)) {
            businessAudit.record(actor, "MODEL_DEPLOYMENT_ROLLED_BACK", "MODEL_DEPLOYMENT", task,
                    "FAILED", reason, "MODEL_DEPLOYMENT_STALE", before, null, null);
            throw conflict("MODEL_DEPLOYMENT_STALE", "The active model changed after the impact preview.");
        }
        int eligible = jdbc.sql("""
                        SELECT COUNT(*) FROM model_version mv JOIN dataset_version dv
                          ON dv.version_id = mv.dataset_version_id
                        WHERE mv.version_id = :target AND mv.task_name = :task
                          AND mv.status IN ('FROZEN', 'ROLLED_BACK')
                          AND dv.lifecycle_status = 'ENABLED'
                        """)
                .param("target", target).param("task", task).query(Integer.class).single();
        if (eligible != 1) {
            businessAudit.record(actor, "MODEL_DEPLOYMENT_ROLLED_BACK", "MODEL_DEPLOYMENT", task,
                    "FAILED", reason, "MODEL_ROLLBACK_TARGET_INVALID", before, null,
                    Map.of("targetVersionId", target));
            throw conflict("MODEL_ROLLBACK_TARGET_INVALID",
                    "The target must be a frozen model for the same task with an enabled dataset.");
        }
        int updated = jdbc.sql("""
                        UPDATE model_deployment
                        SET active_version_id = :target, rollback_version_id = :expected,
                            deployed_at = CURRENT_TIMESTAMP(6), deployed_by = :actor
                        WHERE task_name = :task AND active_version_id = :expected
                        """)
                .param("target", target).param("expected", expected)
                .param("actor", actor.username()).param("task", task).update();
        if (updated != 1) throw conflict("MODEL_DEPLOYMENT_STALE", "The active model changed during rollback.");
        jdbc.sql("UPDATE model_version SET status = 'ROLLED_BACK' WHERE version_id = :version")
                .param("version", expected).update();
        jdbc.sql("UPDATE model_version SET status = 'ACTIVE' WHERE version_id = :version")
                .param("version", target).update();
        ModelDeployment after = deployment(task);
        jdbc.sql("""
                        INSERT INTO model_deployment_event(
                            id, task_name, expected_active_version_id, previous_active_version_id,
                            target_version_id, reason, actor_user_id)
                        VALUES (:id, :task, :expected, :previous, :target, :reason, :actor)
                        """)
                .param("id", UUID.randomUUID().toString()).param("task", task)
                .param("expected", expected).param("previous", before.activeVersionId())
                .param("target", target).param("reason", reason)
                .param("actor", actor.userId().toString()).update();
        List<String> modules = affectedModules(task);
        businessAudit.record(actor, "MODEL_DEPLOYMENT_ROLLED_BACK", "MODEL_DEPLOYMENT", task,
                "SUCCEEDED", reason, null, before, after, Map.of("affectedModules", modules));
        redis.delete("transparency:current");
        return new AdminDtos.ModelRollbackResult(after, modules);
    }

    public AdminDtos.AuditList auditEvents(
            int requestedPage, int requestedSize, OffsetDateTime from, OffsetDateTime to,
            UUID actorUserId, String action, String targetType, String outcome) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        long total = auditCount(from, to, actorUserId, action, targetType, outcome);
        List<AuditEvent> items = jdbc.sql("""
                        SELECT ae.id, ae.actor_user_id, actor.display_name actor_display_name,
                               ae.active_role, ae.action, ae.target_type, ae.target_id,
                               outcome, reason, error_code, CAST(before_json AS CHAR) before_json,
                               CAST(after_json AS CHAR) after_json,
                               CAST(metadata_json AS CHAR) metadata_json, correlation_id, ae.created_at
                        FROM admin_audit_event ae
                        JOIN user_account actor ON actor.id = ae.actor_user_id
                        WHERE (:fromAt IS NULL OR ae.created_at >= :fromAt)
                          AND (:toAt IS NULL OR ae.created_at <= :toAt)
                          AND (:actorId IS NULL OR ae.actor_user_id = :actorId)
                          AND (:action IS NULL OR ae.action = :action)
                          AND (:targetType IS NULL OR ae.target_type = :targetType)
                          AND (:outcome IS NULL OR ae.outcome = :outcome)
                        ORDER BY ae.created_at DESC, ae.id DESC LIMIT :limit OFFSET :offset
                        """)
                .param("fromAt", from).param("toAt", to)
                .param("actorId", actorUserId == null ? null : actorUserId.toString())
                .param("action", blankToNull(action)).param("targetType", blankToNull(targetType))
                .param("outcome", blankToNull(outcome)).param("limit", size).param("offset", page * size)
                .query((rs, row) -> new AuditEvent(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("actor_user_id")), rs.getString("actor_display_name"),
                        rs.getString("active_role"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("outcome"), rs.getString("reason"), rs.getString("error_code"),
                        rs.getString("before_json"), rs.getString("after_json"), rs.getString("metadata_json"),
                        uuid(rs.getString("correlation_id")), rs.getObject("created_at", OffsetDateTime.class))).list();
        return new AdminDtos.AuditList(items, total, page, size);
    }

    public AuditEvent auditEvent(UUID auditId) {
        return auditEvents(0, 100, null, null, null, null, null, null).items().stream()
                .filter(item -> item.id().equals(auditId)).findFirst()
                .orElseGet(() -> jdbc.sql("""
                        SELECT ae.id, ae.actor_user_id, actor.display_name actor_display_name,
                               ae.active_role, ae.action, ae.target_type, ae.target_id,
                               outcome, reason, error_code, CAST(before_json AS CHAR) before_json,
                               CAST(after_json AS CHAR) after_json,
                               CAST(metadata_json AS CHAR) metadata_json, correlation_id, ae.created_at
                        FROM admin_audit_event ae
                        JOIN user_account actor ON actor.id = ae.actor_user_id
                        WHERE ae.id = :id
                        """).param("id", auditId.toString()).query((rs, row) -> new AuditEvent(
                                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("actor_user_id")),
                                rs.getString("actor_display_name"), rs.getString("active_role"),
                                rs.getString("action"), rs.getString("target_type"),
                                rs.getString("target_id"), rs.getString("outcome"), rs.getString("reason"),
                                rs.getString("error_code"), rs.getString("before_json"), rs.getString("after_json"),
                                rs.getString("metadata_json"), uuid(rs.getString("correlation_id")),
                                rs.getObject("created_at", OffsetDateTime.class)))
                        .optional().orElseThrow(() -> notFound("AUDIT_EVENT_NOT_FOUND", "The audit event does not exist.")));
    }

    public String exportAuditEvents(
            OffsetDateTime from, OffsetDateTime to, UUID actorUserId,
            String action, String targetType, String outcome) {
        StringBuilder csv = new StringBuilder("\uFEFFid,createdAt,actorDisplayName,actorUserId,activeRole,action,targetType,targetId,outcome,reason,errorCode,correlationId\r\n");
        int page = 0;
        AdminDtos.AuditList batch;
        do {
            if (page >= 1000) {
                throw conflict("AUDIT_EXPORT_TOO_LARGE", "The filtered audit export exceeds 100,000 rows.");
            }
            batch = auditEvents(page++, 100, from, to, actorUserId, action, targetType, outcome);
            for (AuditEvent item : batch.items()) {
                csv.append(csv(item.id())).append(',').append(csv(item.createdAt())).append(',')
                        .append(csv(item.actorDisplayName())).append(',').append(csv(item.actorUserId())).append(',')
                        .append(csv(item.activeRole())).append(',')
                        .append(csv(item.action())).append(',').append(csv(item.targetType())).append(',')
                        .append(csv(item.targetId())).append(',').append(csv(item.outcome())).append(',')
                        .append(csv(item.reason())).append(',').append(csv(item.errorCode())).append(',')
                        .append(csv(item.correlationId())).append("\r\n");
            }
        } while ((long) page * batch.size() < batch.total());
        return csv.toString();
    }

    private void changeRole(UUID userId, String oldRole, String newRole) {
        if (!"ADMIN".equals(oldRole) && hasBusinessReferences(userId, oldRole)) {
            throw conflict("USER_ROLE_HAS_HISTORY", "An account with business history cannot change role.");
        }
        if ("TEACHER".equals(oldRole)) jdbc.sql("DELETE FROM teacher_profile WHERE user_id = :id")
                .param("id", userId.toString()).update();
        if ("STUDENT".equals(oldRole)) jdbc.sql("DELETE FROM student_profile WHERE user_id = :id")
                .param("id", userId.toString()).update();
        if ("COUNSELOR".equals(oldRole)) jdbc.sql("DELETE FROM counselor_profile WHERE user_id = :id")
                .param("id", userId.toString()).update();
        jdbc.sql("UPDATE user_role SET role_code = :role WHERE user_id = :id")
                .param("role", newRole).param("id", userId.toString()).update();
        createProfile(userId, newRole);
    }

    private void replaceRoles(EduTwinPrincipal actor, UUID userId,
            Set<String> before, Set<String> after) {
        for (String revoked : before.stream().filter(role -> !after.contains(role)).toList()) {
            jdbc.sql("DELETE FROM user_role WHERE user_id = :id AND role_code = :role")
                    .param("id", userId.toString()).param("role", revoked).update();
            roleHistory(userId, revoked, "REVOKED", actor.userId(), "Administrator role replacement");
        }
        for (String granted : after.stream().filter(role -> !before.contains(role)).toList()) {
            jdbc.sql("INSERT INTO user_role(user_id, role_code, granted_by) VALUES (:id, :role, :actor)")
                    .param("id", userId.toString()).param("role", granted)
                    .param("actor", actor.userId().toString()).update();
            roleHistory(userId, granted, "GRANTED", actor.userId(), "Administrator role replacement");
            createProfile(userId, granted);
        }
    }

    private void roleHistory(UUID userId, String role, String event, UUID actor, String reason) {
        jdbc.sql("""
                INSERT INTO user_role_history(id, user_id, role_code, event_type, changed_by, reason)
                VALUES (:id, :userId, :role, :event, :actor, :reason)
                """).param("id", UUID.randomUUID().toString()).param("userId", userId.toString())
                .param("role", role).param("event", event).param("actor", actor.toString())
                .param("reason", reason).update();
    }

    private boolean hasBusinessReferences(UUID id, String role) {
        String sql = switch (role) {
            case "TEACHER" -> "SELECT COUNT(*) FROM teaching_assignment WHERE teacher_id = :id";
            case "COUNSELOR" -> "SELECT COUNT(*) FROM counselor_scope WHERE counselor_id = :id";
            default -> """
                    SELECT (SELECT COUNT(*) FROM course_enrollment WHERE student_id = :id)
                         + (SELECT COUNT(*) FROM answer_event WHERE student_id = :id)
                         + (SELECT COUNT(*) FROM learning_activity_event WHERE student_id = :id)
                         + (SELECT COUNT(*) FROM lms_submission WHERE student_id = :id)
                    """;
        };
        return jdbc.sql(sql).param("id", id.toString()).query(Integer.class).single() > 0;
    }

    private void createProfile(UUID id, String role) {
        if ("TEACHER".equals(role)) {
            jdbc.sql("INSERT IGNORE INTO teacher_profile(user_id, staff_key) VALUES (:id, :key)")
                    .param("id", id.toString()).param("key", "admin-created-" + id).update();
        } else if ("STUDENT".equals(role)) {
            jdbc.sql("INSERT IGNORE INTO student_profile(user_id, synthetic, synthetic_key, split_name) VALUES (:id, FALSE, NULL, 'test')")
                    .param("id", id.toString()).update();
        } else if ("COUNSELOR".equals(role)) {
            jdbc.sql("INSERT IGNORE INTO counselor_profile(user_id) VALUES (:id)")
                    .param("id", id.toString()).update();
        }
    }

    private AdminUser user(UUID id) {
        return jdbc.sql("""
                        SELECT u.id, u.username, u.display_name, u.enabled, u.must_change_password,
                               u.origin_type, u.created_at, u.updated_at, u.last_active_role,
                               GROUP_CONCAT(ur.role_code ORDER BY ur.role_code) role_codes
                        FROM user_account u JOIN user_role ur ON ur.user_id = u.id WHERE u.id = :id
                        GROUP BY u.id, u.username, u.display_name, u.enabled, u.must_change_password,
                                 u.origin_type, u.created_at, u.updated_at, u.last_active_role
                        """).param("id", id.toString()).query((rs, row) -> mapUser(rs)).optional()
                .orElseThrow(() -> notFound("ADMIN_USER_NOT_FOUND", "The account does not exist."));
    }

    private AdminUser mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> roles = List.of(rs.getString("role_codes").split(","));
        String activeRole = rs.getString("last_active_role");
        if (activeRole == null || !roles.contains(activeRole)) activeRole = preferredRole(new LinkedHashSet<>(roles));
        return new AdminUser(UUID.fromString(rs.getString("id")), rs.getString("username"),
                rs.getString("display_name"), activeRole, roles, rs.getBoolean("enabled"),
                rs.getBoolean("must_change_password"), rs.getString("origin_type"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private DatasetVersion dataset(String id) {
        return datasets().items().stream().filter(item -> item.versionId().equals(id)).findFirst()
                .orElseThrow(() -> notFound("DATASET_VERSION_NOT_FOUND", "The dataset version does not exist."));
    }

    private ModelDeployment deployment(String taskName) {
        return deployments().items().stream()
                .filter(item -> item.taskName().equals(taskName))
                .findFirst()
                .orElseThrow(() -> notFound(
                        "MODEL_DEPLOYMENT_NOT_FOUND", "The model deployment does not exist."));
    }

    private static List<String> affectedModules(String taskName) {
        return switch (taskName) {
            case "NEXT_CORRECT" -> List.of("练习推荐", "知识追踪");
            case "RISK" -> List.of("课程学习风险", "风险干预工单", "辅导员班级对比");
            case "EXPLANATION" -> List.of("学情画像", "诊断解释");
            default -> List.of("模型推理");
        };
    }

    private long auditCount(
            OffsetDateTime from, OffsetDateTime to, UUID actorUserId,
            String action, String targetType, String outcome) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM admin_audit_event
                        WHERE (:fromAt IS NULL OR created_at >= :fromAt)
                          AND (:toAt IS NULL OR created_at <= :toAt)
                          AND (:actorId IS NULL OR actor_user_id = :actorId)
                          AND (:action IS NULL OR action = :action)
                          AND (:targetType IS NULL OR target_type = :targetType)
                          AND (:outcome IS NULL OR outcome = :outcome)
                        """)
                .param("fromAt", from).param("toAt", to)
                .param("actorId", actorUserId == null ? null : actorUserId.toString())
                .param("action", blankToNull(action)).param("targetType", blankToNull(targetType))
                .param("outcome", blankToNull(outcome)).query(Long.class).single();
    }

    private void requireRole(UUID userId, String role) {
        int matches = jdbc.sql("""
                        SELECT COUNT(*) FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                        WHERE u.id = :id AND u.enabled = TRUE AND ur.role_code = :role
                        """).param("id", userId.toString()).param("role", role).query(Integer.class).single();
        if (matches != 1) throw conflict("ADMIN_ASSIGNMENT_ROLE_INVALID", "The selected account has the wrong role.");
    }

    private int activeAdminCount() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                        WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                        """).query(Integer.class).single();
    }

    private void lockActiveAdministrators() {
        jdbc.sql("""
                        SELECT u.id FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                        WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                        ORDER BY u.id FOR UPDATE
                        """).query(String.class).list();
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }

    private int count(String sql) { return jdbc.sql(sql).query(Integer.class).single(); }

    private void audit(EduTwinPrincipal actor, String action, String targetType,
            String targetId, Object before, Object after) {
        businessAudit.record(actor, action, targetType, targetId, "SUCCEEDED",
                null, null, before, after, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\r") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String role(String value) {
        String normalized = required(value, "role", 30).toUpperCase();
        if (!ROLES.contains(normalized))
            throw badRequest("ADMIN_ROLE_INVALID", "Role must be ADMIN, TEACHER, STUDENT, or COUNSELOR.");
        return normalized;
    }

    private static Set<String> roles(List<String> values, String compatibilityRole) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) values.forEach(value -> result.add(role(value)));
        if (compatibilityRole != null && !compatibilityRole.isBlank()) result.add(role(compatibilityRole));
        if (result.isEmpty()) throw badRequest("ADMIN_ROLE_INVALID", "At least one role is required.");
        return result;
    }

    private static String preferredRole(Set<String> roles) {
        return List.of("STUDENT", "COUNSELOR", "TEACHER", "ADMIN").stream()
                .filter(roles::contains).findFirst().orElseThrow();
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw badRequest("ADMIN_FIELD_INVALID", field + " is required and must not exceed " + max + " characters.");
        return value.trim();
    }

    private static DomainException badRequest(String code, String detail) { return new DomainException(HttpStatus.BAD_REQUEST, code, detail); }
    private static DomainException conflict(String code, String detail) { return new DomainException(HttpStatus.CONFLICT, code, detail); }
    private static DomainException notFound(String code, String detail) { return new DomainException(HttpStatus.NOT_FOUND, code, detail); }
}
