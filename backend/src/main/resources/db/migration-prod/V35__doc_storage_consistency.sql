-- ---------------------------------------------------------------------------
-- V35 档案库 DB-Storage 一致性加固（L1）
-- ---------------------------------------------------------------------------
-- 背景：当前 upload/delete 路径下，DB 与 storage 存在以下漂移：
--   1. upload 时 storage.put 成功 + DB.save 失败 → 文件孤儿（storage 有，DB 无）
--   2. delete 时 storage.delete 抛错 → 记 warn 仍删 DB → 文件孤儿
--   3. ArchiveLibrary 删时 storage.deletePrefix 抛错 → DB/menu/perm 全清但 storage 残留
-- 计划：
--   - 服务层改为「两阶段 + 补偿」（upload: DB 先建 PENDING_UPLOAD → storage.put →
--     UPDATE READY；delete: status=PENDING_DELETE → storage.delete → DB.delete）
--   - 失败回滚 status 抛 BizException 让前端可见
-- - 本迁移只补「并发上传同名文件」的兜底唯一约束；status 字段 + 默认值 1
--   sys_document 表 V17 已存在，仅扩枚举语义（1=READY / 2=PENDING_UPLOAD /
--   3=PENDING_DELETE / 4=MISSING）。@PrePersist 已兜 null → 1。

-- 部分唯一索引：仅对 status != MISSING(4) 的行约束 storage_key 唯一
-- 目的：upload 改两阶段后，两个并发请求同时 uniqueKey() 通过 storage.exists 检查
-- （一个 PENDING_UPLOAD 还没落 storage，另一个 storage.exists=false 通过），落到
-- storage.put 时互相覆盖 —— 用部分唯一索引让 DB 兜底：
--   第二个 save() 因 unique 约束失败 → 事务回滚 → 抛 BizException(409)
-- SQLite / PostgreSQL 都支持部分索引；WHERE 子句完全一致。
CREATE UNIQUE INDEX IF NOT EXISTS idx_doc_storage_key_active
  ON sys_document (storage_key)
  WHERE status != 4;

-- 兼容：列上现有的 status 值含义不变
-- 1 = READY（旧称 ACTIVE；继续作为「正常显示」状态）
-- 2 = PENDING_UPLOAD（upload 阶段 1：DB 行已建，storage.put 未完成）
-- 3 = PENDING_DELETE（delete 阶段 1：DB 标待删，storage.delete 未完成）
-- 4 = MISSING（DB 有但 storage 找不到；reconcile 标记；列表/统计可见，但
--     preview/download 会被 FileController.raw() 拒掉）