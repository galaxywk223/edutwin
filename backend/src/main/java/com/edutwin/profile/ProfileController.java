package com.edutwin.profile;

import com.edutwin.identity.EduTwinPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/profile")
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) { this.service = service; }

    @GetMapping public ProfileDtos.Profile get() { return service.get(principal()); }
    @PutMapping public ProfileDtos.Profile update(@RequestBody ProfileDtos.ProfileUpdate request) {
        return service.update(principal(), request);
    }
    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
