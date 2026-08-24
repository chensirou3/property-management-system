package com.propertyops.pms.security;

import java.util.List;
import java.util.Set;

public record AuthPrincipal(
        String userId,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions,
        Set<String> projectIds
) {
    public boolean hasProject(String projectId) {
        return roles.contains("PLATFORM_ADMIN") || projectIds.contains(projectId);
    }

    public List<String> authorities() {
        return java.util.stream.Stream.concat(
                        roles.stream().map(role -> "ROLE_" + role),
                        permissions.stream())
                .distinct()
                .toList();
    }
}

