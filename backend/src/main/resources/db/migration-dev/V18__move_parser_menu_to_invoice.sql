-- V18: 将「解析 API」菜单从「系统设置」移到「发票管理」，并重命名为「解析设置」。
-- 背景：解析 API 仅供发票上传时使用，归属发票管理更符合业务；
--       历史名称 OCR 不再准确，统一为「解析设置」。
-- 幂等：UPDATE 条件确保已迁移 / fresh install (V2 直接落到 parent_id=10) 都不重复执行。

-- 重命名 + 改 component + 改 parent_id + 调整 sort 排到发票目录末尾
UPDATE sys_menu
SET name       = 'Parser Settings',
    parent_id  = 10,
    path       = '/invoice/parser',
    component  = 'invoice/parser/Index',
    sort       = 12
WHERE id = 25;