package com.edutwin.counselor;

import com.edutwin.identity.EduTwinPrincipal;
import java.util.UUID;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/counselor")
@PreAuthorize("hasRole('COUNSELOR')")
public class CounselorController {
    private final CounselorService service;

    public CounselorController(CounselorService service) {
        this.service = service;
    }

    @GetMapping("/students")
    public CounselorDtos.StudentPage students(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer cohortYear,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String riskBand,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.students(
                principal().userId(), query, cohortYear, className, riskBand, page, size);
    }

    @GetMapping("/students/{studentId}/overview")
    public CounselorDtos.StudentOverview overview(@PathVariable UUID studentId) {
        return service.overview(principal().userId(), studentId);
    }

    @GetMapping("/classes/compare")
    public CounselorDtos.ClassComparisonResult compareClasses(
            @RequestParam List<String> classNames,
            @RequestParam(defaultValue = "30D") String period) {
        return service.compareClasses(principal().userId(), classNames, period);
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
