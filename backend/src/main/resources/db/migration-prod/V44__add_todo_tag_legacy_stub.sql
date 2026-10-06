-- V40: 创建 sys_todo_tag_legacy 占位表（unblock 后端启动）
--
-- 背景：用户把 todo tag 重构为 dict-based（sys_dict_type='todo_tag'），
-- 但 TodoTaskService 还有遗留代码引用 TodoTag 实体 / TodoTagRepository。
-- 本次 stub 重建 TodoTag 实体指向 sys_todo_tag_legacy 表，仅为编译+启动通过，
-- 运行时不会真有数据写入（V43 后所有 tag 都走字典）。
-- 待用户后续清理 TodoTaskService 内的 tagRepo 引用后可删除本表 + 实体。
CREATE TABLE IF NOT EXISTS sys_todo_tag_legacy (
    id       BIGSERIAL PRIMARY KEY,
    owner_id BIGINT,
    name     VARCHAR(64) NOT NULL
);