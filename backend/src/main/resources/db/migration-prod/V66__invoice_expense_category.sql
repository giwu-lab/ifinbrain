-- V66: 发票费用类别（expense_category_label）
--
-- fapiao_cn 识别接口新增返回字段 expense_category_label（费用类别），
-- ExtractItem 用 @JsonNaming(SnakeCaseStrategy) 映射为 expenseCategoryLabel。
-- 本迁移把该字段落到 invoice_record，让发票列表能按费用类别查看。
--
-- 存 label（中文可读文案）而不是 code：识别侧已把「类别编码 + 中文名」翻译好，
-- 直接存 label 避免前端再维护一份类别字典；类别文案变动时历史数据保留当时的表述。
--
-- 可空且无默认值：老数据识别时该字段不存在，必须留 NULL 而不是塞空串占位。
ALTER TABLE invoice_record
  ADD COLUMN IF NOT EXISTS expense_category_label VARCHAR(128);

COMMENT ON COLUMN invoice_record.expense_category_label IS '费用类别（fapiao_cn 返回的 expense_category_label；老数据为 NULL）';

-- 列表支持按费用类别筛选时用；当前列表只展示不筛选，索引先不建 —— 单列低基数，
-- 收益有限，等真的加了筛选再补（见 AGENTS §4.1「不预先优化」）。
