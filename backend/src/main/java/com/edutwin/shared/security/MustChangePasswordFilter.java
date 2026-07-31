package com.edutwin.shared.security;

import com.edutwin.identity.EduTwinPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/login");
    private final ProblemAccessDeniedHandler accessDeniedHandler;

    public MustChangePasswordFilter(ProblemAccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof EduTwinPrincipal principal
                && principal.mustChangePassword()
                && !ALLOWED.contains(request.getRequestURI())
                && !request.getRequestURI().startsWith("/actuator/health")) {
            accessDeniedHandler.handle(
                    request, response, new AccessDeniedException("Password change required."));
            return;
        }
        chain.doFilter(request, response);
    }
}
