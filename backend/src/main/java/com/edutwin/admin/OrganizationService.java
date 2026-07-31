package com.edutwin.admin;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.notification.NotificationService;
import com.edutwin.shared.web.DomainException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    private static final Set<String> TYPES = Set.of("COLLEGE", "DEPARTMENT", "MAJOR", "CLASS");
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final BusinessAuditService businessAudit;

    public OrganizationService(JdbcClient jdbc, NotificationService notifications,
            BusinessAuditService businessAudit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.businessAudit = businessAudit;
    }

    public OrganizationDtos.OrganizationList organizations() {
        List<FlatOrganization> flat = jdbc.sql("""
                SELECT id, code, display_name, unit_type, parent_id, enabled
                FROM organization_unit ORDER BY code
                """).query((rs, row) -> new FlatOrganization(UUID.fromString(rs.getString("id")),
                        rs.getString("code"), rs.getString("display_name"), rs.getString("unit_type"),
                        rs.getString("parent_id") == null ? null : UUID.fromString(rs.getString("parent_id")),
                        rs.getBoolean("enabled"))).list();
        Map<UUID, List<FlatOrganization>> children = new LinkedHashMap<>();
        flat.forEach(item -> children.computeIfAbsent(item.parentId(), key -> new ArrayList<>()).add(item));
        List<OrganizationDtos.OrganizationUnit> roots = children.getOrDefault(null, List.of()).stream()
                .map(item -> toTree(item, children)).toList();
        return new OrganizationDtos.OrganizationList(roots, flat.size());
    }

    @Transactional
    public OrganizationDtos.OrganizationUnit create(EduTwinPrincipal actor,
            OrganizationDtos.OrganizationWrite request) {
        String targetId = auditTarget(request == null ? "new" : request.code());
        try {
            lockOrganizationHierarchy();
            ValidatedOrganization value = validate(request, null);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO organization_unit(id, code, display_name, unit_type, parent_id, enabled)
                    VALUES (:id, :code, :name, :type, :parentId, :enabled)
                    """).param("id", id.toString()).param("code", value.code()).param("name", value.name())
                    .param("type", value.type()).param("parentId", string(value.parentId()))
                    .param("enabled", value.enabled()).update();
            OrganizationDtos.OrganizationUnit created = organization(id);
            audit(actor, "ORGANIZATION_CREATED", "ORGANIZATION", id.toString(), null, created);
            return created;
        } catch (RuntimeException exception) {
            auditFailure(actor, "ORGANIZATION_CREATED", "ORGANIZATION", targetId, exception);
            throw exception;
        }
    }

    @Transactional
    public OrganizationDtos.OrganizationUnit update(EduTwinPrincipal actor, UUID id,
            OrganizationDtos.OrganizationWrite request) {
        try {
            lockOrganizationHierarchy();
            OrganizationDtos.OrganizationUnit before = organization(id);
            ValidatedOrganization value = validate(request, id);
            if (!value.enabled()) {
                OrganizationDtos.OrganizationReferences references = references(id);
                if (references.users() + references.courses() > 0)
                    throw conflict("ORGANIZATION_IN_USE", "An organization with active references cannot be disabled.");
            }
            jdbc.sql("""
                    UPDATE organization_unit SET code = :code, display_name = :name,
                        unit_type = :type, parent_id = :parentId, enabled = :enabled WHERE id = :id
                    """).param("code", value.code()).param("name", value.name()).param("type", value.type())
                    .param("parentId", string(value.parentId())).param("enabled", value.enabled())
                    .param("id", id.toString()).update();
            OrganizationDtos.OrganizationUnit after = organization(id);
            audit(actor, "ORGANIZATION_UPDATED", "ORGANIZATION", id.toString(), before, after);
            return after;
        } catch (RuntimeException exception) {
            auditFailure(actor, "ORGANIZATION_UPDATED", "ORGANIZATION", id.toString(), exception);
            throw exception;
        }
    }

    public OrganizationDtos.OrganizationReferences references(UUID id) {
        organization(id);
        int users = jdbc.sql("""
                SELECT COUNT(*) FROM user_organization_membership
                WHERE organization_id = :id AND ended_at IS NULL
                """).param("id", id.toString()).query(Integer.class).single();
        int courses = jdbc.sql("SELECT COUNT(*) FROM course WHERE organization_id = :id")
                .param("id", id.toString()).query(Integer.class).single();
        return new OrganizationDtos.OrganizationReferences(users, courses);
    }

    @Transactional
    public void replaceUserOrganization(EduTwinPrincipal actor, UUID userId, UUID organizationId) {
        OrganizationAssignment before = currentPrimaryOrganization(userId);
        try {
            if (jdbc.sql("SELECT COUNT(*) FROM user_account WHERE id = :id")
                    .param("id", userId.toString()).query(Integer.class).single() != 1)
                throw notFound("ADMIN_USER_NOT_FOUND", "The account does not exist.");
            OrganizationDtos.OrganizationUnit organization = organization(organizationId);
            if (!organization.enabled()) throw conflict("ORGANIZATION_DISABLED", "The organization is disabled.");
            jdbc.sql("""
                    UPDATE user_organization_membership SET ended_at = CURRENT_TIMESTAMP(6)
                    WHERE user_id = :userId AND membership_type = 'PRIMARY' AND ended_at IS NULL
                    """).param("userId", userId.toString()).update();
            jdbc.sql("""
                    INSERT INTO user_organization_membership(
                        user_id, organization_id, membership_type, started_at, ended_at)
                    VALUES (:userId, :organizationId, 'PRIMARY', CURRENT_TIMESTAMP(6), NULL)
                    ON DUPLICATE KEY UPDATE started_at = CURRENT_TIMESTAMP(6), ended_at = NULL
                    """).param("userId", userId.toString()).param("organizationId", organizationId.toString()).update();
            notifications.create(userId, "ORGANIZATION_CHANGED", "组织归属已更新",
                    "账号的组织归属已更新为" + organization.displayName() + "。", "/profile",
                    "organization-change:" + userId + ":" + organizationId + ":" + System.currentTimeMillis(), null);
            audit(actor, "USER_ORGANIZATION_REPLACED", "USER", userId.toString(), before,
                    new OrganizationAssignment(organization.organizationId(), organization.displayName()));
        } catch (RuntimeException exception) {
            auditFailure(actor, "USER_ORGANIZATION_REPLACED", "USER", userId.toString(), exception);
            throw exception;
        }
    }

    public OrganizationDtos.AcademicTermList terms() {
        var items = jdbc.sql("""
                SELECT id, code, display_name, starts_on, ends_on, enabled
                FROM academic_term ORDER BY starts_on DESC, code
                """).query((rs, row) -> new OrganizationDtos.AcademicTerm(
                        UUID.fromString(rs.getString("id")), rs.getString("code"),
                        rs.getString("display_name"), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getBoolean("enabled"))).list();
        return new OrganizationDtos.AcademicTermList(items, items.size());
    }

    @Transactional
    public OrganizationDtos.AcademicTerm createTerm(EduTwinPrincipal actor,
            OrganizationDtos.AcademicTermWrite request) {
        String targetId = request == null ? "new" : request.code();
        try {
            ValidatedTerm value = validateTerm(request);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO academic_term(id, code, display_name, starts_on, ends_on, enabled)
                    VALUES (:id, :code, :name, :startsOn, :endsOn, :enabled)
                    """).param("id", id.toString()).param("code", value.code()).param("name", value.name())
                    .param("startsOn", value.startsOn()).param("endsOn", value.endsOn())
                    .param("enabled", value.enabled()).update();
            OrganizationDtos.AcademicTerm created = term(id);
            audit(actor, "ACADEMIC_TERM_CREATED", "ACADEMIC_TERM", id.toString(), null, created);
            return created;
        } catch (RuntimeException exception) {
            auditFailure(actor, "ACADEMIC_TERM_CREATED", "ACADEMIC_TERM", targetId, exception);
            throw exception;
        }
    }

    @Transactional
    public OrganizationDtos.AcademicTerm updateTerm(EduTwinPrincipal actor, UUID id,
            OrganizationDtos.AcademicTermWrite request) {
        try {
            OrganizationDtos.AcademicTerm before = term(id);
            ValidatedTerm value = validateTerm(request);
            if (!value.enabled() && jdbc.sql("SELECT COUNT(*) FROM course WHERE academic_term_id = :id")
                    .param("id", id.toString()).query(Integer.class).single() > 0)
                throw conflict("ACADEMIC_TERM_IN_USE", "A referenced academic term cannot be disabled.");
            jdbc.sql("""
                    UPDATE academic_term SET code = :code, display_name = :name, starts_on = :startsOn,
                        ends_on = :endsOn, enabled = :enabled WHERE id = :id
                    """).param("code", value.code()).param("name", value.name())
                    .param("startsOn", value.startsOn()).param("endsOn", value.endsOn())
                    .param("enabled", value.enabled()).param("id", id.toString()).update();
            OrganizationDtos.AcademicTerm after = term(id);
            audit(actor, "ACADEMIC_TERM_UPDATED", "ACADEMIC_TERM", id.toString(), before, after);
            return after;
        } catch (RuntimeException exception) {
            auditFailure(actor, "ACADEMIC_TERM_UPDATED", "ACADEMIC_TERM", id.toString(), exception);
            throw exception;
        }
    }

    private OrganizationDtos.OrganizationUnit organization(UUID id) {
        return jdbc.sql("""
                SELECT id, code, display_name, unit_type, parent_id, enabled
                FROM organization_unit WHERE id = :id
                """).param("id", id.toString()).query((rs, row) -> new OrganizationDtos.OrganizationUnit(
                        UUID.fromString(rs.getString("id")), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("unit_type"),
                        rs.getString("parent_id") == null ? null : UUID.fromString(rs.getString("parent_id")),
                        rs.getBoolean("enabled"), List.of())).optional()
                .orElseThrow(() -> notFound("ORGANIZATION_NOT_FOUND", "The organization does not exist."));
    }

    private OrganizationDtos.AcademicTerm term(UUID id) {
        return terms().items().stream().filter(item -> item.termId().equals(id)).findFirst()
                .orElseThrow(() -> notFound("ACADEMIC_TERM_NOT_FOUND", "The academic term does not exist."));
    }

    private OrganizationAssignment currentPrimaryOrganization(UUID userId) {
        return jdbc.sql("""
                SELECT ou.id, ou.display_name
                FROM user_organization_membership membership
                JOIN organization_unit ou ON ou.id = membership.organization_id
                WHERE membership.user_id = :userId
                  AND membership.membership_type = 'PRIMARY' AND membership.ended_at IS NULL
                """).param("userId", userId.toString())
                .query((rs, row) -> new OrganizationAssignment(
                        UUID.fromString(rs.getString("id")), rs.getString("display_name")))
                .optional().orElse(null);
    }

    private void lockOrganizationHierarchy() {
        jdbc.sql("SELECT id FROM organization_unit ORDER BY id FOR UPDATE")
                .query(String.class).list();
    }

    private void audit(EduTwinPrincipal actor, String action, String targetType,
            String targetId, Object before, Object after) {
        businessAudit.record(actor, action, targetType, targetId, "SUCCEEDED",
                null, null, before, after, null);
    }

    private void auditFailure(EduTwinPrincipal actor, String action, String targetType,
            String targetId, RuntimeException exception) {
        businessAudit.record(actor, action, targetType, targetId, "FAILED",
                exception.getMessage(), errorCode(exception), null, null, null);
    }

    private static String errorCode(RuntimeException exception) {
        return exception instanceof DomainException domain ? domain.code()
                : exception.getClass().getSimpleName();
    }

    private static String auditTarget(String value) {
        if (value == null || value.isBlank()) return "new";
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private ValidatedOrganization validate(OrganizationDtos.OrganizationWrite request, UUID id) {
        if (request == null) throw badRequest("ORGANIZATION_INVALID", "Organization data is required.");
        String code = required(request.code(), 80).toUpperCase();
        if (!code.matches("[A-Z0-9][A-Z0-9_-]*"))
            throw badRequest("ORGANIZATION_CODE_INVALID", "Organization codes must be stable uppercase identifiers.");
        String type = required(request.unitType(), 20).toUpperCase();
        if (!TYPES.contains(type)) throw badRequest("ORGANIZATION_TYPE_INVALID", "The organization type is invalid.");
        if (id != null && id.equals(request.parentId()))
            throw badRequest("ORGANIZATION_PARENT_INVALID", "An organization cannot be its own parent.");
        validateParent(id, request.parentId());
        return new ValidatedOrganization(code, required(request.displayName(), 160), type,
                request.parentId(), request.enabled() == null || request.enabled());
    }

    private void validateParent(UUID id, UUID parentId) {
        if (parentId == null) return;
        organization(parentId);
        if (id == null) return;

        Set<UUID> visited = new HashSet<>();
        UUID ancestorId = parentId;
        while (ancestorId != null) {
            if (id.equals(ancestorId) || !visited.add(ancestorId))
                throw badRequest("ORGANIZATION_PARENT_INVALID",
                        "An organization cannot be moved below one of its descendants.");
            ancestorId = organization(ancestorId).parentId();
        }
    }

    private static ValidatedTerm validateTerm(OrganizationDtos.AcademicTermWrite request) {
        if (request == null || request.startsOn() == null || request.endsOn() == null
                || request.endsOn().isBefore(request.startsOn()))
            throw badRequest("ACADEMIC_TERM_INVALID", "Academic term dates are invalid.");
        return new ValidatedTerm(required(request.code(), 50).toUpperCase(),
                required(request.displayName(), 120), request.startsOn(), request.endsOn(),
                request.enabled() == null || request.enabled());
    }

    private OrganizationDtos.OrganizationUnit toTree(FlatOrganization item,
            Map<UUID, List<FlatOrganization>> children) {
        return new OrganizationDtos.OrganizationUnit(item.id(), item.code(), item.name(), item.type(),
                item.parentId(), item.enabled(), children.getOrDefault(item.id(), List.of()).stream()
                        .map(child -> toTree(child, children)).toList());
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw badRequest("ADMIN_FIELD_INVALID", "A required field is blank or too long.");
        return value.trim();
    }
    private static String string(UUID id) { return id == null ? null : id.toString(); }
    private static DomainException badRequest(String code, String detail) { return new DomainException(HttpStatus.BAD_REQUEST, code, detail); }
    private static DomainException conflict(String code, String detail) { return new DomainException(HttpStatus.CONFLICT, code, detail); }
    private static DomainException notFound(String code, String detail) { return new DomainException(HttpStatus.NOT_FOUND, code, detail); }
    private record FlatOrganization(UUID id, String code, String name, String type, UUID parentId, boolean enabled) {}
    private record ValidatedOrganization(String code, String name, String type, UUID parentId, boolean enabled) {}
    private record ValidatedTerm(String code, String name, LocalDate startsOn, LocalDate endsOn, boolean enabled) {}
    private record OrganizationAssignment(UUID organizationId, String displayName) {}
}
