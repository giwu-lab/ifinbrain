package com.anomalyco.ifinbrain.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.anomalyco.ifinbrain.web.config.JwtProperties;
import jakarta.annotation.PostConstruct;
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

    /** HMAC-SHA256 需要 >= 256 bit 密钥；JJWT 对更短的密钥直接抛 WeakKeyException。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties props;
    private SecretKey key;

    /**
     * 启动期强校验：access-secret 未配置或长度不足时立即失败，而不是等到第一次签发 token
     * 才暴露。与 CryptoUtil.init() 对 master-key 的处理保持同一种安全姿态。
     */
    @PostConstruct
    void init() {
        key();
    }

    private SecretKey key() {
        if (key == null) {
            String secret = props.getAccessSecret();
            // Spring Boot 默认 ignoreUnresolvablePlaceholders=true：JWT_ACCESS_SECRET 未配置时
            // ${JWT_ACCESS_SECRET} 不会报错，而是以字面量形式原样绑定进来。该字面量就写在源码里，
            // 等于一个公开的固定密钥，必须显式拦掉，否则等于用更弱的方式回到了硬编码兜底。
            if (secret == null || secret.isBlank() || secret.startsWith("${")) {
                throw new IllegalStateException(
                        "ifinbrain.jwt.access-secret must be set (env JWT_ACCESS_SECRET). " +
                        "There is deliberately no built-in default: a fallback secret committed to " +
                        "source lets anyone who read it forge valid tokens.");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "ifinbrain.jwt.access-secret must be >= " + MIN_SECRET_BYTES + " bytes. " +
                        "Generate one with: openssl rand -base64 48");
            }
            key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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