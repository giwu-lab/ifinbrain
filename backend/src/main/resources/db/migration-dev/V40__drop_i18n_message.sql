-- 国际化统一收口到 TS 文件（前端 zh-CN.ts / en-US.ts）
-- 删除 sys_i18n_message 表、相关索引、3 个 i18n:* 权限码及其 role_perm 绑定
-- 对应前端：
--   - 删除 dict 管理页的"国际化"tab（I18nPanel.vue）
--   - 删除 api/i18n.ts、stores/i18n.ts 的 DB 调用
--   - 删除 main.ts 启动预加载 i18nStore.load()
--   - 删除 i18n-export.mjs 脚本与 backend/.../i18n/defaults.json

DELETE FROM sys_role_perm
  WHERE perm_id IN (
    SELECT id FROM sys_permission
     WHERE code IN ('i18n:view', 'i18n:update', 'i18n:delete')
  );
DELETE FROM sys_permission
 WHERE code IN ('i18n:view', 'i18n:update', 'i18n:delete');

DROP INDEX IF EXISTS idx_i18n_namespace;
DROP TABLE IF EXISTS sys_i18n_message;