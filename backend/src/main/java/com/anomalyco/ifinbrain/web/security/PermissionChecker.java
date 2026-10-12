package com.anomalyco.ifinbrain.web.security;

import com.anomalyco.ifinbrain.web.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component("perm")
@RequiredArgsConstructor
public class PermissionChecker {

    public boolean has(String code) {
        return currentPerms().contains(code);
    }

    /**
     * 当前用户持有的全部权限码；未登录 / 无 principal 返回空集合（调用方不必判 null）。
     *
     * <p>为什么需要「全量」而不是逐个 {@link #has}：有些列表的可见性是**按数据行动态拼码**的
     * （如档案库 {@code documents:{slug}:view}，slug 存在库里）。逐行 has 意味着必须先把整表
     * 拉出来再逐行过滤 —— 那样就没法做 DB 分页了。取全量 perm 反推出「有权 slug 列表」，
     * 再用 {@code SlugIn} 把过滤条件下推给数据库，分页才真正成立。
     */
    public Set<String> currentPerms() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Set.of();
        Object principal = auth.getPrincipal();
        if (principal instanceof AuthPrincipal ap) {
            return ap.getPerms() == null ? Set.of() : Set.copyOf(ap.getPerms());
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
            Set<String> out = new HashSet<>();
            for (var a : ud.getAuthorities()) {
                String v = a.getAuthority();
                if (v != null && v.startsWith(AUTH_PERM_PREFIX)) out.add(v.substring(AUTH_PERM_PREFIX.length()));
            }
            return out;
        }
        return Set.of();
    }

    /** Spring Security 授权前缀，AuthPrincipal 之外那条通路用；与 {@link #has} 保持同一常量 */
    private static final String AUTH_PERM_PREFIX = "PERM_";

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