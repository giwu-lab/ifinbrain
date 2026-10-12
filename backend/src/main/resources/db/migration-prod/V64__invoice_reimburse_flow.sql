-- V58: 发票报销 —— 审批流开关
--
-- 背景：报销弹窗新增「发起审批流程」开关。
--   - 关闭：确认后直接入库，报销状态 = APPROVED（沿用现有行为）
--   - 打开：确认后入库 + 启动审批流程，状态 = PENDING；
--           审批通过 → APPROVED（已报销），驳回 → REJECTED（已驳回）
--
-- 为什么必须加状态列：报销单号（DB 列 verification_no）在**提交瞬间**就要写入，
-- 因为它同时是 storageKey 的目录层级 fapiao/{报销单号}/。所以「单号非空 = 已报销」
-- 这个旧判定无法区分「审批中」——不新增列就没法表达「审批通过才算已报销」。
--
-- ---------------------------------------------------------------------------
-- 1. 状态列
-- ---------------------------------------------------------------------------
-- NONE 未报销 / PENDING 审批中 / APPROVED 已报销 / REJECTED 已驳回
ALTER TABLE invoice_record
  ADD COLUMN IF NOT EXISTS reimburse_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

ALTER TABLE invoice_record
  ADD COLUMN IF NOT EXISTS flow_instance_id BIGINT;

COMMENT ON COLUMN invoice_record.reimburse_status IS '报销状态：NONE 未报销 / PENDING 审批中 / APPROVED 已报销 / REJECTED 已驳回';
COMMENT ON COLUMN invoice_record.flow_instance_id IS '关联的审批流实例 id（sys_flow_instance.id）';

CREATE INDEX IF NOT EXISTS idx_invoice_reimburse_status ON invoice_record(reimburse_status);

-- ---------------------------------------------------------------------------
-- 2. 存量回填
-- ---------------------------------------------------------------------------
-- 历史数据一律按「已报销」认定：这些行是在「单号必填」的旧逻辑下入库的，
-- 旧列表页也是按单号非空判定已报销的，回填成别的状态会让存量单据集体变灰。
UPDATE invoice_record
   SET reimburse_status = 'APPROVED'
 WHERE verification_no IS NOT NULL
   AND TRIM(verification_no) <> ''
   AND reimburse_status = 'NONE';

-- ---------------------------------------------------------------------------
-- 3. 权限
-- ---------------------------------------------------------------------------
-- invoice:reimburse:submit —— 发票模块侧发起报销（commit 时校验）
INSERT INTO sys_permission (code, name, type)
SELECT 'invoice:reimburse:submit', 'Invoice reimburse submit', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'invoice:reimburse:submit');

-- design:flow:expense-sample:deploy —— 流程级 perm：允许 operator 看到并发起
-- 「expense-sample」这条流程。FlowDefineController 的 launchable 清单与
-- FlowInstanceController.start 的 requireAny 都已经按 `design:flow:{code}:deploy`
-- 这个约定实现，这里只需把权限授出去。
INSERT INTO sys_permission (code, name, type)
SELECT 'design:flow:expense-sample:deploy', 'Start expense-sample flow', 'API'
 WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'design:flow:expense-sample:deploy');

INSERT INTO sys_role_perm (role_id, perm_id)
SELECT r.id, p.id
  FROM sys_role r, sys_permission p
 WHERE r.code IN ('admin', 'operator')
   AND p.code IN ('invoice:reimburse:submit', 'design:flow:expense-sample:deploy')
   AND NOT EXISTS (
     SELECT 1 FROM sys_role_perm rp
      WHERE rp.role_id = r.id AND rp.perm_id = p.id
   );

-- 注意（未在此处改动，留档）：
--   审批人点「通过 / 驳回」走 FlowTaskController，硬编码要求 design:flow:deploy，
--   而该 perm 按 V34 只给了 admin。也就是说当前只有 admin 能审批报销单。
--   若要 operator 也能审批，需要单独决定是否放宽审批端权限 —— 那是设计侧的
--   权限模型调整，不属于本次「报销发起」范围。