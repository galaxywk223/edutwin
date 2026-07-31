package com.edutwin.risk;

import com.edutwin.identity.EduTwinPrincipal;
import java.util.List;
import java.util.UUID;
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

@RestController
@RequestMapping("/api/v1/risk")
public class RiskInterventionController {
    private final RiskInterventionService service;

    public RiskInterventionController(RiskInterventionService service) {
        this.service = service;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CasePage cases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.cases(principal(), status, assignedTo, page, size);
    }

    @PostMapping("/cases")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail create(@RequestBody RiskDtos.CreateCaseRequest request) {
        return service.createManualCase(principal(), request);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail detail(@PathVariable UUID caseId) {
        return service.detail(principal(), caseId);
    }

    @PutMapping("/cases/{caseId}/assignment")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail assign(
            @PathVariable UUID caseId, @RequestBody RiskDtos.AssignmentRequest request) {
        return service.assign(principal(), caseId, request);
    }

    @PutMapping("/cases/{caseId}/status")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail transition(
            @PathVariable UUID caseId, @RequestBody RiskDtos.StatusRequest request) {
        return service.transition(principal(), caseId, request);
    }

    @PostMapping("/cases/{caseId}/notes")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail addNote(
            @PathVariable UUID caseId, @RequestBody RiskDtos.NoteRequest request) {
        return service.addNote(principal(), caseId, request);
    }

    @PostMapping("/cases/{caseId}/actions")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail addAction(
            @PathVariable UUID caseId, @RequestBody RiskDtos.ActionRequest request) {
        return service.addAction(principal(), caseId, request);
    }

    @PutMapping("/cases/{caseId}/actions/{actionId}/status")
    @PreAuthorize("hasAnyRole('TEACHER', 'COUNSELOR')")
    public RiskDtos.CaseDetail updateAction(
            @PathVariable UUID caseId,
            @PathVariable UUID actionId,
            @RequestBody RiskDtos.ActionStatusRequest request) {
        return service.updateAction(principal(), caseId, actionId, request);
    }

    @GetMapping("/me/actions")
    @PreAuthorize("hasRole('STUDENT')")
    public List<RiskDtos.StudentActionProjection> studentActions() {
        return service.studentActions(principal());
    }

    @PostMapping("/me/actions/{actionId}/feedback")
    @PreAuthorize("hasRole('STUDENT')")
    public RiskDtos.StudentActionProjection addFeedback(
            @PathVariable UUID actionId, @RequestBody RiskDtos.FeedbackRequest request) {
        return service.addFeedback(principal(), actionId, request);
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
