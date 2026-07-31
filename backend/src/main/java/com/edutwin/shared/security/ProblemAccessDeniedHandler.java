package com.edutwin.shared.security;

import com.edutwin.shared.web.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter responseWriter;

    public ProblemAccessDeniedHandler(ProblemResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        responseWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "The authenticated account cannot access this resource.");
    }
}
