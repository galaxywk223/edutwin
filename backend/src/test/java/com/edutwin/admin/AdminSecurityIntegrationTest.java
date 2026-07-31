package com.edutwin.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityIntegrationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void administratorOwnsGovernanceEndpointsButCannotEnterBusinessApis() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview").with(authentication(auth(UserRole.ADMIN))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/overview").with(authentication(auth(UserRole.STUDENT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/courses").with(authentication(auth(UserRole.ADMIN))))
                .andExpect(status().isForbidden());
    }

    private static UsernamePasswordAuthenticationToken auth(UserRole role) {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                UUID.randomUUID(), "security-" + role.getValue().toLowerCase(), role.getValue(), "",
                role, Set.of(), true);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
    }
}
