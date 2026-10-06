-- V41: 修正「流程运行」菜单 component 字段
--
-- V37 迁移写错了 component = 'design/flow/Workflow'，导致：
--   - SidebarLayout.vue menuName() 用 sys_menu.component 拼 i18n key
--   - 拼成 'menu.design.flow.Workflow'（不存在）→ 回退到 m.name = 'Flow Runtime'
--   - 中文 locale 下显示英文 "Flow Runtime"，而其他菜单正确显示中文
--
-- 正确值应为 'design/flow-runtime/Index' —— 与 router meta.component / builtInViews key 一致
-- 对应 i18n key = menu.design.flow-runtime.Index
--   zh-CN.ts: '流程运行'
--   en-US.ts: 'Flow Runtime'
--
-- 注意：sys_i18n_message 表已在 V40 删除，i18n 统一收口到前端 TS 文件。
-- 本次修复不依赖 sys_i18n_message —— 只修正 sys_menu.component，让 menuName() 解析到正确 key。

UPDATE sys_menu
   SET component = 'design/flow-runtime/Index'
 WHERE id = 34
   AND component = 'design/flow/Workflow';