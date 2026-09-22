package com.anomalyco.fapiao.web.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.anomalyco.fapiao.web.entity.SysUser;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public class AppUserDetails implements UserDetails {

    private final SysUser user;
    private final List<String> roleCodes;
    private final List<String> permCodes;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permCodes.stream()
                .map(p -> new SimpleGrantedAuthority("PERM_" + p))
                .collect(Collectors.toList());
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return user.getStatus() != null && user.getStatus() == 1; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return user.getStatus() != null && user.getStatus() == 1; }
}