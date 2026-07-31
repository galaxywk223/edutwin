package com.edutwin.shared.security;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("scopeAuthorization")
public class ScopeAuthorization {

    private final AccessScopeRepository repository;

    public ScopeAuthorization(AccessScopeRepository repository) {
        this.repository = repository;
    }

    public boolean canAccessCourse(Authentication authentication, UUID courseId) {
        Optional<EduTwinPrincipal> resolved = principal(authentication);
        if (resolved.isEmpty() || courseId == null) {
            return false;
        }
        EduTwinPrincipal principal = resolved.orElseThrow();
        if (!claimAllowsCourse(principal, courseId)) {
            return false;
        }
        if (principal.role() == UserRole.STUDENT) {
            return repository.studentHasActiveEnrollment(principal.userId(), courseId);
        }
        if (principal.role() == UserRole.TEACHER) {
            return repository.teacherHasTeachingAssignment(principal.userId(), courseId);
        }
        return false;
    }

    public boolean canAccessStudent(
            Authentication authentication, UUID courseId, UUID studentId) {
        Optional<EduTwinPrincipal> resolved = principal(authentication);
        if (resolved.isEmpty() || courseId == null || studentId == null) {
            return false;
        }
        EduTwinPrincipal principal = resolved.orElseThrow();
        if (!claimAllowsCourse(principal, courseId)) {
            return false;
        }
        if (principal.role() == UserRole.STUDENT) {
            return principal.userId().equals(studentId)
                    && repository.studentHasActiveEnrollment(principal.userId(), courseId);
        }
        if (principal.role() == UserRole.TEACHER) {
            return repository.teacherCanAccessStudent(principal.userId(), courseId, studentId);
        }
        return false;
    }

    public boolean canAccessJob(Authentication authentication, UUID jobId) {
        Optional<EduTwinPrincipal> resolved = principal(authentication);
        if (resolved.isEmpty() || jobId == null) {
            return false;
        }
        EduTwinPrincipal principal = resolved.orElseThrow();
        Optional<AccessScopeRepository.JobScope> scope = repository.findJobScope(jobId);
        if (scope.isEmpty()) {
            return false;
        }
        AccessScopeRepository.JobScope jobScope = scope.orElseThrow();
        if (!claimAllowsCourse(principal, jobScope.courseId())) {
            return false;
        }
        if (principal.role() == UserRole.STUDENT) {
            return principal.userId().equals(jobScope.studentId())
                    && repository.studentCanAccessJob(principal.userId(), jobId);
        }
        if (principal.role() == UserRole.TEACHER) {
            return repository.teacherCanAccessJob(principal.userId(), jobId);
        }
        return false;
    }

    private static Optional<EduTwinPrincipal> principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof EduTwinPrincipal principal)
                || !principal.enabled()) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    private static boolean claimAllowsCourse(EduTwinPrincipal principal, UUID courseId) {
        return principal.accessibleCourseIds().contains(courseId);
    }
}
