package com.anomalyco.fapiao.web.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public class AuthPrincipal {

    private final Long userId;
    private final String username;
    private final List<String> roles;
    private final List<String> perms;

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return perms.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority("PERM_" + p))
                .collect(Collectors.toList());
    }
}