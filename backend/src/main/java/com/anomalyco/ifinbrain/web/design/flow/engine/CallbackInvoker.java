package com.anomalyco.ifinbrain.web.design.flow.engine;

import com.anomalyco.ifinbrain.web.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 流程回调触发器。
 *
 * <p>支持 3 种 callback（Phase 4 实现 HTTP + SpEL）：
 * <ul>
 *     <li>URL      → HTTP POST 通知业务系统（异步）</li>
 *     <li>SPEL     → 解析表达式执行（同步）</li>
 *     <li>JAVA_METHOD → 调 Spring Bean 方法（Phase 4 简化：暂留口，后续按需补）</li>
 * </ul>
 *
 * <p>回调失败不影响主流程：捕获异常后丢弃，记 warn 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackInvoker {

    private final ApplicationContext ctx;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final WebClient webClient = WebClient.builder().build();

    public enum Type { URL, SPEL, JAVA_METHOD }

    public record CallbackSpec(Type type, String value) {}

    /**
     * 触发 callback（异步 fire-and-forget）。
     *
     * @param cb        回调规格（null 或 value 为空 → 直接跳过）
     * @param variables 流程变量
     * @param event     事件名（SUBMIT / APPROVED / REJECTED / COMPLETED）
     */
    public void invoke(CallbackSpec cb, Map<String, Object> variables, String event) {
        if (cb == null || cb.value() == null || cb.value().isBlank()) return;
        try {
            switch (cb.type()) {
                case URL -> invokeUrl(cb.value(), variables, event);
                case SPEL -> invokeSpel(cb.value(), variables);
                case JAVA_METHOD -> log.warn("JAVA_METHOD callback not yet implemented: {}", cb.value());
            }
        } catch (Exception e) {
            log.warn("Callback failed: type={}, value={}, err={}", cb.type(), cb.value(), e.getMessage());
        }
    }

    private void invokeUrl(String url, Map<String, Object> variables, String event) {
        // 异步，不阻塞主流程
        webClient.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(BodyInserters.fromValue(Map.of("event", event, "variables", variables)))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Callback URL failed: url={}, err={}", url, e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
}

    private void invokeSpel(String spel, Map<String, Object> variables) {
        Expression e = parser.parseExpression(spel);
        StandardEvaluationContext ectx = new StandardEvaluationContext();
        if (variables != null) ectx.setVariables(variables);
        ectx.setVariable("ctx", ctx);
        e.getValue(ectx);
    }

    /** 解析 callback 字符串：'URL:http://...' / 'SPEL:${...}' / 'JAVA_METHOD:bean.method' */
    public static CallbackSpec parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int idx = s.indexOf(':');
        if (idx < 0) throw new BizException(400, "callback 格式错误: " + s);
        String t = s.substring(0, idx).trim().toUpperCase();
        String v = s.substring(idx + 1).trim();
        Type type;
        try { type = Type.valueOf(t); }
        catch (Exception e) { throw new BizException(400, "callback 类型不支持: " + t); }
        return new CallbackSpec(type, v);
    }
}