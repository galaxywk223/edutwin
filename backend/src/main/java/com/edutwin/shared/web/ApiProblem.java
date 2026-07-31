package com.edutwin.shared.web;

import java.util.List;

public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String traceId,
        List<FieldViolation> violations) {

    public ApiProblem {
        violations = List.copyOf(violations);
    }

    public record FieldViolation(String field, String message) {}
}
