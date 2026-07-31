package com.edutwin.profile;

import java.util.List;
import java.util.UUID;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record OrganizationRef(UUID organizationId, String code, String displayName, String unitType) {}
    public record Profile(UUID userId, String username, String displayName, String officialId,
            List<String> roles, List<OrganizationRef> organizations, String email, String phone,
            String avatarKey, String themeColor) {}
    public record ProfileUpdate(String email, String phone, String avatarKey, String themeColor) {}
}
