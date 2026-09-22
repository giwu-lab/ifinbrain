-- V7: 补全 Invoice 子菜单的权限码
-- 背景：V2 seed 里 Upload(id=11)/Records(id=12) 故意设为 NULL。
--   后续 UI 改造为下拉选权限码后， 这两条业务菜单仍无码， 表格列显示 '—'。
--   现回填：Upload 对应 invoice:upload (功能匹配)、Records 对应 invoice:list (列表页匹配)。
-- 幂等：NOT EXISTS 保证重复执行 / fresh install 都不会重复或冲突。

UPDATE sys_menu
SET permission = 'invoice:upload'
WHERE id = 11
  AND (permission IS NULL OR permission = '');

UPDATE sys_menu
SET permission = 'invoice:list'
WHERE id = 12
  AND (permission IS NULL OR permission = '');