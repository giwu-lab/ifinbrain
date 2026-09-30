-- ---------------------------------------------------------------------------
-- V36 档案库 storage 后端迁移日志（L2 配套表）
-- ---------------------------------------------------------------------------
-- 背景：L2 迁移工具要把 sys_document.storage_type 从 'local' 批量迁到 's3'（或反之）。
--   每行搬运状态需记录：
--     - 成功：size_bytes + sha256 双重校验
--     - 失败：error_msg 留痕便于 retry
--   - 幂等：重跑同 from→to + same storage_key 已 status=1 视为已迁移（跳过）
--   - 进度：COUNT(*) WHERE status=1 = 已迁；COUNT(*) WHERE status=2 = 失败
--
-- 设计：
--   - from_type / to_type 用 VARCHAR(16)：未来若引入 azure / gcs 也兼容
--   - storage_key 与 sys_document.storage_key 同长度（512）
--   - status SMALLINT：1=ok / 2=fail；保留扩展（3=running / 4=skipped）
--   - started_at 自动填；finished_at 由 service 完成后置入

CREATE TABLE storage_migration_log (
  id              INTEGER       PRIMARY KEY AUTOINCREMENT,
  from_type       VARCHAR(16)   NOT NULL,
  to_type         VARCHAR(16)   NOT NULL,
  storage_key     VARCHAR(512)  NOT NULL,
  status          SMALLINT      NOT NULL DEFAULT 1,   -- 1=ok / 2=fail
  error_msg       TEXT,
  size_bytes      BIGINT,
  sha256          CHAR(64),
  started_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at     TIMESTAMP
);

-- 进度查询：按时间倒序取最新一批
CREATE INDEX idx_sml_started
  ON storage_migration_log(started_at DESC);

-- 幂等 / 跳过查重用：同 from→to + 同 key 最新一条记录
CREATE INDEX idx_sml_storage_key
  ON storage_migration_log(storage_key, started_at DESC);