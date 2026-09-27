package com.anomalyco.ifinbrain.web.security;

import com.anomalyco.ifinbrain.web.common.Result;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                Claims claims = jwtUtil.parse(token);
                Long userId = jwtUtil.userId(claims);
                String username = claims.get("u", String.class);
                List<String> roles = JwtUtil.roles(claims);
                List<String> perms = JwtUtil.perms(claims);
                if (userId != null && username != null
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    AuthPrincipal principal = new AuthPrincipal(userId, username, roles, perms);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException ex) {
                // 有 Bearer token 但解析失败 → 必须 401，不要放行匿名（review L19）。
                // 旧逻辑"放行匿名"会让攻击者用伪造 JWT 探测 server 行为；permitAll 端点
                // （如 /api/auth/login）不带 token 即可，不会走这个分支。
                log.warn("JWT parse failed, returning 401: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
                writeUnauthorized(response, "Invalid or expired token");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                Result.fail(401, msg));
    }
}