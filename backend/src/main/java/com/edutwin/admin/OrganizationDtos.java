package com.edutwin.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrganizationDtos {
    private OrganizationDtos() {}

    public record OrganizationUnit(UUID organizationId, String code, String displayName,
            String unitType, UUID parentId, boolean enabled, List<OrganizationUnit> children) {}
    public record OrganizationList(List<OrganizationUnit> items, int total) {}
    public record OrganizationWrite(String code, String displayName, String unitType,
            UUID parentId, Boolean enabled) {}
    public record OrganizationReferences(int users, int courses) {}
    public record UserOrganizationReplace(UUID organizationId) {}
    public record AcademicTerm(UUID termId, String code, String displayName,
            LocalDate startsOn, LocalDate endsOn, boolean enabled) {}
    public record AcademicTermWrite(String code, String displayName,
            LocalDate startsOn, LocalDate endsOn, Boolean enabled) {}
    public record AcademicTermList(List<AcademicTerm> items, int total) {}
}
