package com.edutwin.admin;

import com.edutwin.ai.AiRuntimeConfigurationService;
import com.edutwin.api.model.TransparencyResponse;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.provenance.TransparencyReadService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminService service;
    private final TransparencyReadService transparency;
    private final AiRuntimeConfigurationService aiConfiguration;

    public AdminController(
            AdminService service,
            TransparencyReadService transparency,
            AiRuntimeConfigurationService aiConfiguration) {
        this.service = service;
        this.transparency = transparency;
        this.aiConfiguration = aiConfiguration;
    }

    @GetMapping("/overview") public AdminDtos.Overview overview() { return service.overview(); }
    @GetMapping("/users") public AdminDtos.UserList users() { return service.users(); }

    @GetMapping("/imports/user-provisioning/templates/{template}")
    public ResponseEntity<byte[]> userImportTemplate(@PathVariable String template) {
        UserProvisioningImportService.TemplateFile file = service.userImportTemplate(template);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PostMapping(value = "/imports/user-provisioning/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminDtos.UserImportPreview previewUserImport(@RequestParam("files") List<MultipartFile> files) {
        return service.previewUserImport(files);
    }

    @PostMapping(value = "/imports/user-provisioning", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminDtos.UserImportResult commitUserImport(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam String expectedSha256) {
        return service.commitUserImport(principal(), files, expectedSha256);
    }

    @PostMapping("/users")
    public ResponseEntity<AdminDtos.AdminUser> createUser(@RequestBody AdminDtos.UserCreateRequest request) {
        AdminDtos.AdminUser created = service.createUser(principal(), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.userId())).body(created);
    }

    @PutMapping("/users/{userId}")
    public AdminDtos.AdminUser updateUser(@PathVariable UUID userId, @RequestBody AdminDtos.UserUpdateRequest request) {
        return service.updateUser(principal(), userId, request);
    }

    @PostMapping("/users/{userId}/password-reset")
    public AdminDtos.TemporaryPassword resetPassword(@PathVariable UUID userId) {
        return service.resetPassword(principal(), userId);
    }

    @GetMapping("/student-groups")
    public AdminDtos.StudentGroupList studentGroups() { return service.studentGroups(); }

    @GetMapping("/counselors/{counselorId}/scopes")
    public AdminDtos.CounselorScopeList counselorScopes(@PathVariable UUID counselorId) {
        return service.counselorScopes(counselorId);
    }

    @PutMapping("/counselors/{counselorId}/scopes")
    public AdminDtos.CounselorScopeList replaceCounselorScopes(
            @PathVariable UUID counselorId,
            @RequestBody AdminDtos.CounselorScopeReplaceRequest request) {
        return service.replaceCounselorScopes(principal(), counselorId, request);
    }

    @GetMapping("/transparency") public TransparencyResponse transparency() { return transparency.get(); }
    @GetMapping("/datasets") public AdminDtos.DatasetList datasets() { return service.datasets(); }

    @GetMapping("/datasets/{versionId}/impact")
    public AdminDtos.DatasetImpact datasetImpact(@PathVariable String versionId) {
        return service.datasetImpact(versionId);
    }

    @PutMapping("/datasets/{versionId}/status")
    public AdminDtos.DatasetVersion changeDatasetStatus(
            @PathVariable String versionId, @RequestBody AdminDtos.StatusRequest request) {
        return service.changeDatasetStatus(principal(), versionId, request.status(), request.reason());
    }

    @GetMapping("/model-deployments")
    public AdminDtos.ModelDeploymentList deployments() { return service.deployments(); }

    @GetMapping("/ai-configuration")
    public AdminDtos.AiConfiguration aiConfiguration() {
        return aiConfiguration.view();
    }

    @PutMapping("/ai-configuration")
    public AdminDtos.AiConfigurationActivationResult activateAiConfiguration(
            @RequestBody AdminDtos.AiConfigurationUpdateRequest request) {
        return aiConfiguration.activate(principal(), request);
    }

    @PostMapping("/ai-configuration/restore-environment")
    public AdminDtos.AiConfigurationActivationResult restoreAiEnvironment(
            @RequestBody AdminDtos.AiConfigurationRestoreRequest request) {
        return aiConfiguration.restoreEnvironment(principal(), request);
    }

    @PostMapping("/model-deployments/{taskName}/rollback")
    public AdminDtos.ModelRollbackResult rollbackDeployment(
            @PathVariable String taskName,
            @RequestBody AdminDtos.ModelRollbackRequest request) {
        return service.rollbackDeployment(principal(), taskName, request);
    }

    @GetMapping("/audit-events")
    public AdminDtos.AuditList auditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String outcome) {
        return service.auditEvents(page, size, from, to, actorUserId, action, targetType, outcome);
    }

    @GetMapping("/audit-events/{auditId}")
    public AdminDtos.AuditEvent auditEvent(@PathVariable UUID auditId) {
        return service.auditEvent(auditId);
    }

    @GetMapping(value = "/audit-events.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportAuditEvents(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String outcome) {
        byte[] csv = service.exportAuditEvents(
                        from, to, actorUserId, action, targetType, outcome)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=edutwin-audit.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
