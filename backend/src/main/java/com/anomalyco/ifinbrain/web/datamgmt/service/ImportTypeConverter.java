package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceSchemaDto;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 单元格文本 → JDBC 绑定值的类型转换。
 *
 * <p>Excel / CSV 读出来一律是 String（或 SheetJS 意义的数字串），目标列类型却是
 * varchar / numeric / date / timestamp / bool 五花八门。这一层是导入里最容易出错、
 * 也最需要「失败要说人话」的地方 —— 用户拿到的应该是「第 37 行的『开票日期』
 * 无法解析为日期：2024-13-45」，而不是 SQLException。
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li>转换失败<b>不抛异常</b>，返回 error 交由调用方收集成 {@code ImportErrorRow}。
 *       导入是批处理，一格坏掉不该让 3 万行全部回滚。</li>
 *   <li>歧义值一律<b>判失败而不是猜</b>。典型是 {@code 12%}：猜成 12 还是 0.12
 *       都是错的（前者放大 100 倍，后者可能该是 120）。宁可让用户回 Excel 改，
 *       也不把错数据静默写进业务库。</li>
 *   <li>格式化类问题（字符串超长、小数位超 scale）默认「修 + 记 warning」，
 *       因为这些几乎总是「用户想表达的值被截了个尾巴」，修掉比丢行体验好。</li>
 * </ul>
 */
@Component
public class ImportTypeConverter {

    /** 转换结果：要么有值，要么有 error；truncated / warning 是「修过了」的痕迹。 */
    public record CellValue(Object value, boolean truncated, String warning, String error) {

        static CellValue ok(Object v) {
            return new CellValue(v, false, null, null);
        }

        static CellValue warn(Object v, String msg) {
            return new CellValue(v, true, msg, null);
        }

        static CellValue fail(String msg) {
            return new CellValue(null, false, null, msg);
        }

        public boolean isOk() {
            return error == null;
        }
    }

    /** 日期：已把 / . 年 月 日 统一成 - 之后，yyyy-M-d 即可吃下 2024-01-31 / 2024-1-31 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-M-d");
    private static final DateTimeFormatter DATE_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 主入口。
     *
     * @param raw            单元格原始文本
     * @param col            目标列元数据
     * @param emptyAsNull    空串是否转 null（默认 true：Excel 里没填 == NULL）
     * @param truncateString 字符串超长时是否截断（false 则判失败，由用户改源文件）
     */
    public CellValue convert(String raw,
                             DatasourceSchemaDto.ColumnSchema col,
                             boolean emptyAsNull,
                             boolean truncateString) {

        String s = normalize(raw);
        boolean empty = s == null || s.isEmpty();

        if (empty) {
            if (emptyAsNull) return CellValue.ok(null);
            return CellValue.ok("string".equals(col.getType()) ? "" : null);
        }

        String type = col.getType() == null ? "string" : col.getType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "number"  -> toNumber(s, col);
            case "date"    -> toDate(s);
            case "datetime"-> toDateTime(s);
            case "boolean" -> toBoolean(s);
            default        -> toString(s, col, truncateString);
        };
    }

    // ==================== 各类型 ====================

    private CellValue toNumber(String s, DatasourceSchemaDto.ColumnSchema col) {
        String t = toHalfWidth(s);
        // 百分比：歧义太大，判失败并给出明确指引（详见类注释「歧义值一律判失败」）
        if (t.endsWith("%")) {
            return CellValue.fail("百分比无法自动转换：请在源文件里改成小数（如 12% → 0.12）再导入");
        }
        // 千分位逗号：1,234.56 → 1234.56
        t = t.replace(",", "").replace(" ", "");
        if (t.isEmpty()) return CellValue.fail("数值内容为空");

        BigDecimal v;
        try {
            v = new BigDecimal(t);
        } catch (NumberFormatException e) {
            return CellValue.fail("不是合法数字: " + s);
        }

        // 小数位超 scale：四舍五入并记 warning（numeric(10,2) 收 3 位小数是常见情况）
        Integer scale = col.getScale();
        if (scale != null && scale >= 0 && v.scale() > scale) {
            BigDecimal rounded = v.setScale(scale, RoundingMode.HALF_UP);
            return CellValue.warn(rounded,
                    "小数位超过 " + scale + " 位，已四舍五入（" + v.toPlainString() + " → " + rounded.toPlainString() + "）");
        }
        return CellValue.ok(v);
    }

    private CellValue toDate(String s) {
        String t = splitDate(s);
        if (t == null) {
            return CellValue.fail("无法解析为日期: " + s);
        }
        LocalDate d = parseDate(t);
        if (d == null) {
            return CellValue.fail("无法解析为日期: " + s + "（支持 2024-01-31 / 2024/1/31 / 20240131 / 2024年1月31日）");
        }
        return CellValue.ok(d);
    }

    private CellValue toDateTime(String s) {
        String t = splitDate(s);
        if (t == null) {
            return CellValue.fail("无法解析为日期时间: " + s);
        }
        LocalDate d = parseDate(t);
        if (d == null) {
            return CellValue.fail("无法解析为日期时间: " + s);
        }
        // 没有时间部分 → 当天 00:00:00（比判失败更符合「用户填了日期忘了填时间」的直觉）
        String timePart = extractTimePart(s);
        LocalTime time = timePart == null || timePart.isEmpty() ? LocalTime.MIDNIGHT : parseTime(timePart);
        if (time == null) {
            return CellValue.fail("日期可解析但时间部分无效: " + s);
        }
        return CellValue.ok(LocalDateTime.of(d, time));
    }

    private CellValue toBoolean(String s) {
        String t = toHalfWidth(s).trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "true", "1", "y", "yes", "是", "真", "t" -> CellValue.ok(Boolean.TRUE);
            case "false", "0", "n", "no", "否", "假", "f"   -> CellValue.ok(Boolean.FALSE);
            default -> CellValue.fail("无法解析为布尔值: " + s + "（支持 true/false、1/0、是/否）");
        };
    }

    private CellValue toString(String s, DatasourceSchemaDto.ColumnSchema col, boolean truncateString) {
        Integer max = col.getMaxLength();
        if (max == null || s.length() <= max) {
            return CellValue.ok(s);
        }
        if (!truncateString) {
            return CellValue.fail("超长：" + s.length() + " 字符，列 " + col.getName() + " 最多 " + max);
        }
        return CellValue.warn(s.substring(0, max),
                "超长已截断：" + s.length() + " → " + max + " 字符");
    }

    // ==================== 归一化 ====================

    /**
     * 基础清洗：全角空格 → 半角、去首尾空白。
     *
     * <p>全角空格（U+3000）在中文 Excel 里非常常见（用户从网页复制、输入法残留），
     * 不清掉会变成 " 金额" 这种查不出来的值。
     */
    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\u3000', ' ').replace('\u00A0', ' ').trim();
        return s;
    }

    /** 全角数字/符号 → 半角。Excel 从中文输入法输入的数字常是全角。 */
    private String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') {          // ０-９
                sb.append((char) (c - '\uFF10' + '0'));
            } else if (c == '\uFF0C') {                     // ，
                sb.append(',');
            } else if (c == '\uFF0E') {                     // ．
                sb.append('.');
            } else if (c == '\uFF0D' || c == '\u2212') {    // － −
                sb.append('-');
            } else if (c == '\uFF05') {                     // ％
                sb.append('%');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 从任意日期写法里抠出「归一化后的日期段」。
     * 返回 null 表示压根不是日期（长度/字符集就不对）。
     */
    private String splitDate(String raw) {
        String s = toHalfWidth(raw).trim()
                .replace("年", "-").replace("月", "-").replace("日", "")
                .replace('/', '-').replace('.', '-')
                .trim();
        if (s.isEmpty()) return null;

        // 分离时间部分：T 或空格
        int sep = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'T' || c == 't' || c == ' ') { sep = i; break; }
        }
        String datePart = (sep >= 0 ? s.substring(0, sep) : s).trim();
        // 去掉可能残留的尾部分隔符（如 "2024年1月31日" → "2024-1-31-"）
        while (datePart.endsWith("-")) {
            datePart = datePart.substring(0, datePart.length() - 1);
        }
        return datePart.isEmpty() ? null : datePart;
    }

    private String extractTimePart(String raw) {
        String s = toHalfWidth(raw).trim().replace('T', ' ');
        int idx = s.indexOf(' ');
        if (idx < 0) return null;
        return s.substring(idx + 1).trim();
    }

    /**
     * 解析日期段。
     *
     * <p>Excel 序列号：纯数字且 ≤ 6 位（1900-01-01 ≈ 1，9999-12-31 ≈ 2958465）
     * 才当序列号；8 位（20240131）走 yyyyMMdd 分支，否则会把 2024 年当序号。
     */
    private LocalDate parseDate(String s) {
        try {
            if (isAllDigits(s)) {
                if (s.length() <= 6) {
                    double serial = Double.parseDouble(s);
                    if (serial >= 1 && serial < 2958466) {
                        return DateUtil.getLocalDateTime(serial).toLocalDate();
                    }
                    return null;
                }
                if (s.length() == 8) {
                    return LocalDate.parse(s, DATE_COMPACT);
                }
                return null;
            }
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseTime(String s) {
        String t = s.replace(':', ' ').replace(".", "").trim();
        if (t.isEmpty()) return LocalTime.MIDNIGHT;
        String digits = t.replace(" ", "");
        if (isAllDigits(digits)) {
            try {
                int len = digits.length();
                if (len == 6) {
                    return LocalTime.of(Integer.parseInt(digits.substring(0, 2)),
                            Integer.parseInt(digits.substring(2, 4)),
                            Integer.parseInt(digits.substring(4, 6)));
                }
                if (len == 4) {
                    return LocalTime.of(Integer.parseInt(digits.substring(0, 2)),
                            Integer.parseInt(digits.substring(2, 4)));
                }
                if (len == 2) {
                    return LocalTime.of(Integer.parseInt(digits), 0);
                }
            } catch (Exception ignore) {
                return null;
            }
            return null;
        }
        // 带 AM/PM 的写法
        String up = s.toUpperCase(Locale.ROOT);
        if (up.endsWith("AM") || up.endsWith("PM")) {
            String ampm = up.substring(up.length() - 2);
            String body = up.substring(0, up.length() - 2).trim().replace(":", "");
            try {
                int h = Integer.parseInt(body);
                int hh = ("PM".equals(ampm) && h < 12) ? h + 12 : ("AM".equals(ampm) && h == 12) ? 0 : h;
                return LocalTime.of(hh, 0);
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}