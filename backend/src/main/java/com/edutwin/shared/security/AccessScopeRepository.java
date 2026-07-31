package com.edutwin.shared.security;

import java.util.Optional;
import java.util.UUID;

public interface AccessScopeRepository {

    boolean studentHasActiveEnrollment(UUID studentId, UUID courseId);

    boolean teacherHasTeachingAssignment(UUID teacherId, UUID courseId);

    boolean teacherCanAccessStudent(UUID teacherId, UUID courseId, UUID studentId);

    Optional<JobScope> findJobScope(UUID jobId);

    boolean studentCanAccessJob(UUID studentId, UUID jobId);

    boolean teacherCanAccessJob(UUID teacherId, UUID jobId);

    record JobScope(UUID jobId, UUID courseId, UUID studentId) {}
}
