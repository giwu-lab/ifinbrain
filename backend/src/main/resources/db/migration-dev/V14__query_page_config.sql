-- 查询页面发布前可配置：参数显示名、表格列 header 文本与默认列宽
-- sys_menu 加 config_json（nullable，TEXT）：JSON {paramLabels, columns}

ALTER TABLE sys_menu ADD COLUMN config_json TEXT;

-- 现有 sys_menu 行默认空配置
UPDATE sys_menu SET config_json = '{}' WHERE config_json IS NULL;