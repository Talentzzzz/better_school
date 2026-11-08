package com.betterschool.trigger.http.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具：principal 为 userId（JwtAuthenticationFilter 写入），角色为 ROLE_xxx 权限
 */
public final class SecurityUtils {

    private static final String ROLE_PREFIX = "ROLE_";

    private SecurityUtils() {
    }

    public static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 匿名请求的 principal 是字符串 "anonymousUser"，不得误当用户 id（UV 计数等判空口径依赖返回 null）
        if (authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication != null && authentication.getPrincipal() instanceof String userId) {
            return userId;
        }
        return null;
    }

    /** 当前用户角色（USER/ADMIN），未登录返回 null */
    public static String currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority();
            if (role != null && role.startsWith(ROLE_PREFIX)) {
                return role.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }
}
