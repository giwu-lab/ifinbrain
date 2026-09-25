package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.dto.auth.LoginReq;
import com.anomalyco.ifinbrain.web.dto.auth.LoginResp;
import com.anomalyco.ifinbrain.web.dto.auth.RefreshReq;
import com.anomalyco.ifinbrain.web.dto.auth.UserInfo;
import com.anomalyco.ifinbrain.web.security.AuthPrincipal;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PermissionChecker perm;

    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public Result<LoginResp> refresh(@Valid @RequestBody RefreshReq req) {
        return Result.ok(authService.refresh(req.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        Long uid = perm.currentUserId();
        if (uid != null) authService.logout(uid);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserInfo> me() {
        Long uid = perm.currentUserId();
        return Result.ok(authService.me(uid));
    }
}