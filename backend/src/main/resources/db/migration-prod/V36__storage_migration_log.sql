-- ---------------------------------------------------------------------------
-- V36 档案库 storage 后端迁移日志（L2 配套表）
-- ---------------------------------------------------------------------------
-- 见 migration-dev/V36__storage_migration_log.sql 详细说明。
-- dev / prod 表结构完全一致。

CREATE TABLE storage_migration_log (
  id              BIGSERIAL     PRIMARY KEY,
  from_type       VARCHAR(16)   NOT NULL,
  to_type         VARCHAR(16)   NOT NULL,
  storage_key     VARCHAR(512)  NOT NULL,
  status          SMALLINT      NOT NULL DEFAULT 1,
  error_msg       TEXT,
  size_bytes      BIGINT,
  sha256          CHAR(64),
  started_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at     TIMESTAMP
);

CREATE INDEX idx_sml_started
  ON storage_migration_log(started_at DESC);

CREATE INDEX idx_sml_storage_key
  ON storage_migration_log(storage_key, started_at DESC);