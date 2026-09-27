package com.anomalyco.ifinbrain.web.config;

import com.anomalyco.ifinbrain.web.security.JwtAuthFilter;
import com.anomalyco.ifinbrain.web.security.RestSecurityHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestSecurityHandlers restSecurityHandlers;
    private final iFinBrainProperties properties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg,
                                                     UserDetailsService uds,
                                                     PasswordEncoder encoder) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return new org.springframework.security.authentication.ProviderManager(List.of(provider));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        List<String> origins = properties.getCors().getAllowedOrigins();
        // 区分严格 origin 与 pattern：精确列表走 setAllowedOrigins，其它走 setAllowedOriginPatterns。
        // dev 场景下既支持 localhost 又支持 LAN IP（192.168.x / 10.x 等），避免 CORS 误拒。
        if (origins != null && !origins.isEmpty()) {
            for (String o : origins) {
                if (o.contains("*") || o.startsWith("regex:") || o.contains("://*.")) {
                    String pattern = o.startsWith("regex:") ? o.substring(6) : o;
                    cors.addAllowedOriginPattern(pattern);
                } else {
                    cors.addAllowedOrigin(o);
                }
            }
        } else {
            cors.addAllowedOriginPattern("*");
        }
        // 始终允许 LAN 上的常见 IP 段（dev 用）；prod 部署在容器内一般走 nginx 反代同源，不会到这里。
        cors.addAllowedOriginPattern("http://localhost:*");
        cors.addAllowedOriginPattern("http://127.0.0.1:*");
        cors.addAllowedOriginPattern("http://192.168.*.*:*");
        cors.addAllowedOriginPattern("http://10.*.*.*:*");
        cors.addAllowedOriginPattern("http://172.16.*.*:*");

        cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of("Authorization"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/actuator/health",
                        "/actuator/info",
                        "/error"
                ).permitAll()
                // 注意：local-served 文件必须走 /api/files/raw（FileController 内做档案库 view 鉴权）。
                // 这里**不再**对 /uploads/** permitAll —— 避免部署 nginx 直连 storage root 时绕过鉴权。
                // 如确需 nginx 直连，必须在 nginx 层做 JWT 校验 / IP allowlist。
                .anyRequest().authenticated()
            )
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint(restSecurityHandlers)
                .accessDeniedHandler(restSecurityHandlers)
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        // 让 Spring Security 的核心 filter 在 async dispatch 时不再过滤，
        // 因为 StreamingResponseBody 之类的流式响应在 async 线程上运行时，
        // SecurityContext 不会跨线程传播，AuthorizationFilter 会拿不到 auth。
        // 权限校验已在 controller 同步阶段完成（perm.require）。
        http.securityContext(c -> c.requireExplicitSave(true));
        return http.build();
    }
}