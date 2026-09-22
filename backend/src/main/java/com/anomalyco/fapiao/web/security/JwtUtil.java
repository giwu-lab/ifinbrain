package com.anomalyco.fapiao.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.anomalyco.fapiao.web.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties props;
    private SecretKey key;

    private SecretKey key() {
        if (key == null) {
            key = Keys.hmacShaKeyFor(props.getAccessSecret().getBytes(StandardCharsets.UTF_8));
        }
        return key;
    }

    public String issueAccess(Long userId, String username, List<String> roles, List<String> perms) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getAccessTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("u", username)
                .claim("r", roles)
                .claim("p", perms)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long refreshTtlSeconds() {
        return props.getRefreshTtlDays() * 86400L;
    }

    public long accessTtlSeconds() {
        return props.getAccessTtlMinutes() * 60L;
    }

    @SuppressWarnings("unchecked")
    public static List<String> roles(Claims claims) {
        Object o = claims.get("r");
        return o instanceof List ? (List<String>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> perms(Claims claims) {
        Object o = claims.get("p");
        return o instanceof List ? (List<String>) o : List.of();
    }

    public Long userId(Claims claims) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Object> issueRefreshClaims(Long userId) {
        return Map.of("uid", userId, "typ", "refresh");
    }
}