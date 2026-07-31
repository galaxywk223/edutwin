package com.edutwin.shared.security;

import com.edutwin.identity.AccountRepository;
import com.edutwin.identity.EduTwinPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenVersionFilter extends OncePerRequestFilter {
    private final AccountRepository accounts;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;

    public TokenVersionFilter(
            AccountRepository accounts, ProblemAuthenticationEntryPoint authenticationEntryPoint) {
        this.accounts = accounts;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken
                && authentication.getPrincipal() instanceof EduTwinPrincipal principal
                && !accounts.isTokenCurrent(principal.userId(), principal.tokenVersion(),
                        principal.role().getValue())) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response,
                    new InsufficientAuthenticationException("The access token is no longer current."));
            return;
        }
        chain.doFilter(request, response);
    }
}
