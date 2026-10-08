package com.anomalyco.ifinbrain.web.datamgmt.dto;

/**
 * 单个单元格级错误。
 *
 * @param rowNo    Excel / CSV 的物理行号（1 基，含表头行 —— 用户在 Excel 里数出来的行号）
 * @param field    源表头名（用户能据此在自己的文件里找到那一列）
 * @param rawValue 出错时的原始值（截断到 200 字符，避免错误日志被长文本撑爆）
 * @param reason   人话原因，直接可读，不出现 SQLException
 */
public record ImportErrorRow(int rowNo, String field, String rawValue, String reason) {

    /** rawValue 展示上限：Excel 里有人误粘了一整段富文本是常事 */
    public static String clip(String v) {
        if (v == null) return "";
        String one = v.replaceAll("[\\r\\n\\t]+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}