-- V24: 「类目」→「档案库」概念重命名
-- 把 sys_menu 类目设置菜单行（id=100）的 name/path/component 改为档案库；
-- 把 sys_permission 中已存在的 "{类目名} 类目{动词}" 显示名同步改为 "{类目名} 档案库{动词}"。
-- 表名 sys_document_category 保留（@Table 注解指向，零数据迁移）。

-- ---------------------------------------------------------------------------
-- 1. 更新 sys_menu 类目设置菜单行（id=100）：name/path/component
-- ---------------------------------------------------------------------------
UPDATE sys_menu
   SET name = 'Archive Library',
       path = '/documents/archive',
       component = 'documents/archive/Index'
 WHERE id = 100;

-- ---------------------------------------------------------------------------
-- 2. sys_permission.name 字段："{类目名} 类目查看/上传/更新/删除" → "{类目名} 档案库查看/上传/更新/删除"
-- 4 个 verb 各一次 UPDATE（避免误伤其他文案）。
-- ---------------------------------------------------------------------------
UPDATE sys_permission
   SET name = REPLACE(name, '类目查看', '档案库查看')
 WHERE name LIKE '%类目查看%';

UPDATE sys_permission
   SET name = REPLACE(name, '类目上传', '档案库上传')
 WHERE name LIKE '%类目上传%';

UPDATE sys_permission
   SET name = REPLACE(name, '类目更新', '档案库更新')
 WHERE name LIKE '%类目更新%';

-- V23 用的是 VERB_ZH.update = "修改"，所以历史 perm 实际命名为 "类目修改"，
-- 上面类目更新 这一 UPDATE 不会命中。补一条把 "类目修改" → "档案库修改"。
UPDATE sys_permission
   SET name = REPLACE(name, '类目修改', '档案库修改')
 WHERE name LIKE '%类目修改%';

UPDATE sys_permission
   SET name = REPLACE(name, '类目删除', '档案库删除')
 WHERE name LIKE '%类目删除%';
