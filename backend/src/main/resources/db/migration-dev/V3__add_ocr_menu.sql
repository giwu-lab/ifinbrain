-- V3: add OCR settings page (permission + menu + admin role binding).
-- Idempotent for already-seeded envs; fresh installs already get these from V2.

INSERT INTO sys_permission (code, name, type)
SELECT 'ocr:view', 'OCR view', 'API'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'ocr:view');

INSERT INTO sys_permission (code, name, type)
SELECT 'ocr:update', 'OCR update', 'API'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'ocr:update');

INSERT INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type, status)
SELECT 25, 20, 'OCR', '/system/customurl', 'system/ocr/Index', 'magic-stick', 5, 'ocr:view', 'MENU', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 25);

-- bind new perms to admin role (id=1)
INSERT INTO sys_role_perm (role_id, perm_id)
SELECT 1, p.id FROM sys_permission p
WHERE p.code IN ('ocr:view','ocr:update')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_perm rp
    WHERE rp.role_id = 1 AND rp.perm_id = p.id
  );