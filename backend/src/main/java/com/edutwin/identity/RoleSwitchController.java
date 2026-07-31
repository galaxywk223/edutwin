package com.edutwin.identity;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RoleSwitchController {
    private final AuthenticationService authenticationService;

    public RoleSwitchController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/role")
    public AuthenticationService.RoleSession switchRole(
            @RequestBody AuthenticationService.RoleSwitchRequest request) {
        EduTwinPrincipal principal = (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return authenticationService.switchRole(principal, request == null ? null : request.role());
    }
}
