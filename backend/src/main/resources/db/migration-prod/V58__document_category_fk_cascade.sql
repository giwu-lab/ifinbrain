-- V58: sys_document.category_id 外键补 ON DELETE CASCADE
--
-- 背景（V17 埋的坑）：
--   V17 建 sys_document 时该外键写成内联的 FOREIGN KEY (category_id)
--   REFERENCES sys_document_category(id)，**没写 ON DELETE 子句** → PG 默认 NO ACTION。
--   也就是说，只要该档案库下还有任意一行 sys_document（不论 status），
--   DELETE FROM sys_document_category 就会被 23503 拒绝。
--
--   而 ArchiveLibraryService.delete() 的守卫只统计 status=1(READY)：
--   「库里只剩 MISSING(4) / PENDING_*(2,3) 的档案」时守卫判定可以删，
--   storage.deletePrefix() 也确实执行完了，然后 libraryRepo.delete(e) 撞 FK → 500。
--   后果是文件已删、DB 行还在，重试会再次失败，且报错完全指不到真因。
--
-- 改法：把该外键换成 ON DELETE CASCADE，删库时 DB 侧一次级联清干净：
--   sys_document_category → sys_document → rag_document_chunk
-- 与 V45 已有的两条 CASCADE 对齐。顺带说明：V45:24 那条 library_id 级联此前
-- **不可达**（被 sys_document 的 NO ACTION 抢先挡住），本迁移后它才真正起作用。
--
-- 为什么不在守卫里改成「数全部状态、一律拦住」：
--   MISSING 行正是「storage 已丢、DB 残留」的垃圾，留着才需要人工逐个清理，
--   一律拦住等于让这类档案库永远删不掉。级联清掉它们才是这个守卫本来想要的语义。
--   READY 行仍由守卫拦住（>0 直接 400），真实档案不会被误删。
--
-- 注意：DROP + ADD CONSTRAINT 会拿 ACCESS EXCLUSIVE 锁并全量校验一遍外键，
--   sys_document 行数量级（千级）下是毫秒级操作，不值得为它上 NOT VALID 两步走。

DO $$
DECLARE
    v_fk text;
BEGIN
    -- 按定义反查约束名，不硬编码：V17 用的是内联表级 FOREIGN KEY，
    -- PG 会自动命名，但迁移不该赌这个名字真的没被改过。
    SELECT c.conname INTO v_fk
      FROM pg_constraint c
      JOIN pg_class     rt ON rt.oid = c.confrelid
      JOIN pg_attribute a  ON a.attrelid = c.conrelid
                          AND a.attnum = c.conkey[1]
     WHERE c.conrelid = 'sys_document'::regclass
       AND c.contype  = 'f'
       AND rt.relname = 'sys_document_category'
       AND a.attname = 'category_id';

    IF v_fk IS NULL THEN
        RAISE EXCEPTION
            '未在 sys_document 上找到指向 sys_document_category 的 category_id 外键，迁移中止。'
            '请人工核对当前约束定义后再决定如何处理 —— 不要盲目重跑本迁移。';
    END IF;

    EXECUTE format('ALTER TABLE sys_document DROP CONSTRAINT %I', v_fk);
    EXECUTE format(
        'ALTER TABLE sys_document ADD CONSTRAINT %I '
        'FOREIGN KEY (category_id) REFERENCES sys_document_category(id) ON DELETE CASCADE',
        v_fk);

    RAISE NOTICE 'sys_document.category_id 外键 % 已改为 ON DELETE CASCADE', v_fk;
END;
$$;
