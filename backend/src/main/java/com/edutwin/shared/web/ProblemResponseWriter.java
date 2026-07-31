package com.edutwin.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail) throws IOException {
        write(request, response, status, code, detail, List.of());
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail,
            List<ApiProblem.FieldViolation> violations) throws IOException {
        ApiProblem problem = create(request, status, code, detail, violations);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    public ApiProblem create(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail,
            List<ApiProblem.FieldViolation> violations) {
        return new ApiProblem(
                "https://edutwin.local/problems/" + code.toLowerCase().replace('_', '-'),
                status.getReasonPhrase(),
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                CorrelationIdFilter.from(request),
                violations);
    }
}
