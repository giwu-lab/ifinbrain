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
 *
 * <p>变量引用两种写法都支持，二者等价：
 * <ul>
 *     <li>{@code #amount} —— SpEL 原生变量语法</li>
 *     <li>{@code vars.amount} —— 本项目文档约定写法，evaluate/validate 会先改写成 {@code #amount}</li>
 * </ul>
 * 例：{@code ${#amount > 5000}} 与 {@code ${vars.amount > 5000}} 行为一致。
 */
@Slf4j
@Component
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 把文档约定的 {@code vars.foo} 改写成 SpEL 的变量引用 {@code #foo}。
     *
     * <p>为什么必须改写：{@link StandardEvaluationContext#setVariables(Map)} 注册的流程变量
     * 在 SpEL 里只能用 {@code #foo} 取值。写成 {@code vars.foo} 时，SpEL 会把它当成
     * 「根对象（这里是 null）的属性访问」，抛 EL1007E
     * {@code Property or field 'vars' cannot be found on null}；该异常被 evaluate() 吞掉后
     * 一律返回 false —— 表现为「条件分支永远不成立」，且只在日志里留一条 WARN，
     * 页面上完全看不出是表达式写错了。
     *
     * <p>类注释与 V38 种子数据声明的用法都是 {@code ${vars.amount > 5000}}，所以这里补上这层兼容。
     * 已经写成 {@code #amount} 的表达式不受影响（本正则只匹配 {@code vars.} 前缀）。
     */
    private static final java.util.regex.Pattern VARS_DOT =
            java.util.regex.Pattern.compile("(?<![\\w.])vars\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static String normalize(String spel) {
        return VARS_DOT.matcher(spel).replaceAll("#$1");
    }

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
            Expression e = parser.parseExpression(normalize(spel));
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
     *
     * <p>走与 {@link #evaluate} 相同的 {@link #normalize}，避免「校验通过但运行时求值失败」。
     */
    public void validate(String expression) {
        if (expression == null || expression.isBlank()) return;
        String expr = expression.trim();
        if (expr.equals("approved") || expr.equals("rejected")) return;
        String spel = expr.startsWith("${") && expr.endsWith("}")
                ? expr.substring(2, expr.length() - 1)
                : expr;
        try {
            parser.parseExpression(normalize(spel));
        } catch (Exception e) {
            throw new BizException(400, "SpEL 表达式不合法: " + e.getMessage());
        }
    }
}