package com.edutwin.shared.web;

import com.edutwin.admin.BusinessMutationAuditInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ProblemResponseWriter problemWriter;

    public ApiExceptionHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiProblem> handleDomain(DomainException exception, HttpServletRequest request) {
        return response(
                request, exception.status(), exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiProblem> handleBadCredentials(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "The username or password is invalid.",
                List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiProblem.FieldViolation> violations = exception.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> new ApiProblem.FieldViolation(
                        error.getField(),
                        error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiProblem.FieldViolation::field))
                .toList();
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "The request contains invalid fields.",
                violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiProblem> handleConstraintValidation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<ApiProblem.FieldViolation> violations = exception.getConstraintViolations()
                .stream()
                .map(violation -> new ApiProblem.FieldViolation(
                        fieldName(violation), violation.getMessage()))
                .sorted(Comparator.comparing(ApiProblem.FieldViolation::field))
                .toList();
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "The request contains invalid fields.",
                violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> handleUnreadable(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "The request body is not valid JSON.",
                List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiProblem> handleConflict(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "The request conflicts with an existing immutable fact.",
                List.of());
    }

    private ResponseEntity<ApiProblem> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail,
            List<ApiProblem.FieldViolation> violations) {
        request.setAttribute(BusinessMutationAuditInterceptor.ERROR_CODE_ATTRIBUTE, code);
        request.setAttribute(BusinessMutationAuditInterceptor.ERROR_REASON_ATTRIBUTE, detail);
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemWriter.create(request, status, code, detail, violations));
    }

    private static String fieldName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
