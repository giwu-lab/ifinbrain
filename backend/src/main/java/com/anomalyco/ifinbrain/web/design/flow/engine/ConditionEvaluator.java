package com.anomalyco.ifinbrain.web.design.flow.engine;

import com.anomalyco.ifinbrain.web.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 条件表达式解析器。
 *
 * <p>支持：
 * <ul>
 *     <li>空 / null  → 默认分支（评估为 true）</li>
 *     <li>"approved" / "rejected"  → 系统常量（用户审批结果）</li>
 *     <li>"${...}"   → Spring SpEL 表达式（如 ${amount > 10000}）</li>
 * </ul>
 *
 * <p>SpEL 上下文内置变量：variables（流程变量 Map）+ result（审批结果 'approved' / 'rejected'）。
 */
@Slf4j
@Component
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 评估条件表达式。
     *
     * @param expression  条件字符串（见类注释格式）
     * @param variables   流程变量 Map（可空）
     * @param result      审批结果 'approved' / 'rejected' / null（用于系统常量）
     * @return true=条件通过 / false=不通过
     */
    public boolean evaluate(String expression, Map<String, Object> variables, String result) {
        if (expression == null || expression.isBlank()) {
            return true;  // 空 = 默认通过（无条件）
        }
        String expr = expression.trim();

        // 系统常量
        if ("approved".equalsIgnoreCase(expr)) {
            return "approved".equalsIgnoreCase(result);
        }
        if ("rejected".equalsIgnoreCase(expr)) {
            return "rejected".equalsIgnoreCase(result);
        }

        // SpEL：${...} 或纯表达式
        String spel = expr.startsWith("${") && expr.endsWith("}")
                ? expr.substring(2, expr.length() - 1)
                : expr;

        try {
            Expression e = parser.parseExpression(spel);
            EvaluationContext ctx = buildContext(variables, result);
            Object value = e.getValue(ctx);
            return Boolean.TRUE.equals(value);
        } catch (Exception ex) {
            log.warn("SpEL evaluation failed: expr={}, err={}", spel, ex.getMessage());
            return false;
        }
    }

    private EvaluationContext buildContext(Map<String, Object> variables, String result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (variables != null) {
            ctx.setVariables(variables);
        }
        ctx.setVariable("result", result);
        return ctx;
    }

    /**
     * 验证 SpEL 表达式格式（编辑器保存前给前端反馈）。
     */
    public void validate(String expression) {
        if (expression == null || expression.isBlank()) return;
        String expr = expression.trim();
        if (expr.equals("approved") || expr.equals("rejected")) return;
        String spel = expr.startsWith("${") && expr.endsWith("}")
                ? expr.substring(2, expr.length() - 1)
                : expr;
        try {
            parser.parseExpression(spel);
        } catch (Exception e) {
            throw new BizException(400, "SpEL 表达式不合法: " + e.getMessage());
        }
    }
}