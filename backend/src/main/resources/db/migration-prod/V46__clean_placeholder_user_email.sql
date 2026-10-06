-- V46: 清除 sys_user.email 里的占位垃圾值
--
-- 背景：V2__seed.sql 给 admin 写死了字面量 '[email protected]'（Cloudflare 邮件
-- 脱敏占位符，被误当成邮箱粘进了种子）。后果是「编辑用户」必挂：弹窗把这个脏值
-- 回填进输入框，保存时 UserUpdateReq.email 上的 @Email 判定非法，直接报
-- "email: 不是一个合法的电子邮件地址" —— 哪怕管理员压根没碰过邮箱这栏。
--
-- 为什么不直接改 V2__seed.sql：V2 已在存量库执行过，改内容会动 Flyway checksum。
-- 本项目 FlywayConfig 在 migrate() 前调了 repair()，checksum 会被自动重算而放过，
-- 但改已执行的历史迁移仍是坏习惯——旧库已插进去的行不会因为改了文件而消失。
-- 所以保留 V2 原样，由本迁移负责把数据洗干净（新库走 V2 插脏值 → V46 擦掉，
-- 最终状态一致）。
--
-- email 是选填字段，NULL 就是「没填」，前端列表渲染为「—」，语义正确。
-- 清理范围刻意保守：只动这个已知占位串和纯空白，不碰任何看起来像真邮箱的值。

UPDATE sys_user
SET email = NULL
WHERE email IS NOT NULL
  AND (email = '[email protected]' OR btrim(email) = '');
