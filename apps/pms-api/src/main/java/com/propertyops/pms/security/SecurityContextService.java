package com.propertyops.pms.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.propertyops.pms.common.api.BusinessException;

@Service
public class SecurityContextService {
    public AuthPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new BusinessException("AUTHENTICATION_REQUIRED", "请重新登录", HttpStatus.UNAUTHORIZED);
        }
        return principal;
    }

    public void requireProject(String projectId) {
        if (!requirePrincipal().hasProject(projectId)) {
            throw new BusinessException("PROJECT_ACCESS_DENIED", "无权访问该项目", HttpStatus.FORBIDDEN);
        }
    }

    public void requirePermission(String permission) {
        if (!requirePrincipal().permissions().contains(permission)) {
            throw new BusinessException("PERMISSION_DENIED", "没有执行该操作的权限", HttpStatus.FORBIDDEN);
        }
    }

    public void requireRole(String role) {
        if (!requirePrincipal().roles().contains(role)) {
            throw new BusinessException("ROLE_REQUIRED", "当前角色不能执行该操作", HttpStatus.FORBIDDEN);
        }
    }
}
