package com.edutwin.identity;

import com.edutwin.api.AuthenticationApi;
import com.edutwin.api.model.AuthSession;
import com.edutwin.api.model.AuthenticatedUser;
import com.edutwin.api.model.LoginRequest;
import com.edutwin.api.model.PasswordChangeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController implements AuthenticationApi {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public ResponseEntity<AuthSession> login(LoginRequest loginRequest) {
        return ResponseEntity.ok(authenticationService.login(loginRequest));
    }

    @Override
    public ResponseEntity<AuthenticatedUser> getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        EduTwinPrincipal principal = (EduTwinPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(AuthenticationService.toAuthenticatedUser(principal));
    }

    @Override
    public ResponseEntity<Void> changePassword(PasswordChangeRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        EduTwinPrincipal principal = (EduTwinPrincipal) authentication.getPrincipal();
        authenticationService.changePassword(
                principal, request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}
