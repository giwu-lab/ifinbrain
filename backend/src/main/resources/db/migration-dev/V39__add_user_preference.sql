-- V39: 用户主题偏好表（per-user ThemeSettings）
--
-- 设计：
--  - user_id 主键 + 外键到 sys_user.id（CASCADE：删用户时清理偏好）
--  - theme_json 存整段 ThemeSettings 的 JSON 字符串（TEXT，PG/SQLite 通用）
--  - 一个用户一行；不存在则前端走 default + 首次 PUT 落库
--  - 启动阶段不需要 seed —— 默认值由前端 stores/theme.ts 提供
--  - updated_at 由 @PrePersist/@PreUpdate 自动维护（SMESTAMP = CURRENT_TIMESTAMP）

CREATE TABLE sys_user_preference (
    user_id     INTEGER       NOT NULL PRIMARY KEY,
    theme_json  TEXT          NOT NULL,
    updated_at  SMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
);