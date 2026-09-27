package com.anomalyco.ifinbrain.web.security;

import com.anomalyco.ifinbrain.web.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Component("perm")
@RequiredArgsConstructor
public class PermissionChecker {

    public boolean has(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthPrincipal ap) {
            return ap.getPerms() != null && ap.getPerms().contains(code);
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
            return ud.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("PERM_" + code));
        }
        return false;
    }

    public void require(String code) {
        if (!has(code)) {
            throw new BizException(403, "Missing permission: " + code);
        }
    }

    public void requireAny(String first, String... rest) {
        if (has(first)) return;
        if (rest != null) for (String c : rest) if (has(c)) return;
        throw new BizException(403, "Missing permission: " + first);
    }

    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof AuthPrincipal ap) return ap.getUserId();
        return null;
    }

    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof AuthPrincipal ap) return ap.getUsername();
        if (auth.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails ud) return ud.getUsername();
        return null;
    }

    /** 当前用户角色 codes，逗号分隔（用于门户页 resolve 按角色匹配）。未登录返回 null。 */
    public String currentRoleCodes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof AuthPrincipal ap) {
            return ap.getRoles() == null ? null : String.join(",", ap.getRoles());
        }
        return null;
    }
}