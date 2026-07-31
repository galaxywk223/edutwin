package com.edutwin.admin;

import com.edutwin.identity.EduTwinPrincipal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service = service; }

    @GetMapping("/organizations") public OrganizationDtos.OrganizationList organizations() { return service.organizations(); }
    @PostMapping("/organizations")
    public ResponseEntity<OrganizationDtos.OrganizationUnit> createOrganization(
            @RequestBody OrganizationDtos.OrganizationWrite request) {
        var created = service.create(principal(), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/organizations/" + created.organizationId())).body(created);
    }
    @PutMapping("/organizations/{organizationId}")
    public OrganizationDtos.OrganizationUnit updateOrganization(@PathVariable UUID organizationId,
            @RequestBody OrganizationDtos.OrganizationWrite request) {
        return service.update(principal(), organizationId, request);
    }
    @GetMapping("/organizations/{organizationId}/references")
    public OrganizationDtos.OrganizationReferences references(@PathVariable UUID organizationId) {
        return service.references(organizationId);
    }
    @PutMapping("/users/{userId}/organization")
    public ResponseEntity<Void> replaceUserOrganization(@PathVariable UUID userId,
            @RequestBody OrganizationDtos.UserOrganizationReplace request) {
        service.replaceUserOrganization(principal(), userId, request.organizationId());
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/academic-terms") public OrganizationDtos.AcademicTermList terms() { return service.terms(); }
    @PostMapping("/academic-terms")
    public ResponseEntity<OrganizationDtos.AcademicTerm> createTerm(
            @RequestBody OrganizationDtos.AcademicTermWrite request) {
        var created = service.createTerm(principal(), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/academic-terms/" + created.termId())).body(created);
    }
    @PutMapping("/academic-terms/{termId}")
    public OrganizationDtos.AcademicTerm updateTerm(@PathVariable UUID termId,
            @RequestBody OrganizationDtos.AcademicTermWrite request) {
        return service.updateTerm(principal(), termId, request);
    }
    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
