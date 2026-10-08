-- V60: 顶级目录「设计开发」更名为「流程管理」，icon 换成流程语义
--
-- 背景：
--   V24 建这个顶级 DIR 时叫「设计开发 / Design Development」，icon 用的是 MagicStick
--   （魔法棒 = 「设计/创造」的通用符号）。但这个目录底下实际装的是三件与流程强相关的东西：
--     id=33 表单设计 / id=32 流程设计 / id=34 流程运行
--   「设计开发」这个名盖不住「流程运行」，尤其 id=34 是运行时管理，跟"设计"关系不大。
--   改成「流程管理 / Flow Management」后，DIR 名和它实际承载的业务对得上。
--
-- icon：MagicStick → Connection
--   MagicStick 的「魔法棒 / 凭空创造」语义是给「设计开发」准备的，更名后不再贴切；
--   Connection（节点相连）正是流程图最核心的视觉符号 —— 流程的本质就是节点之间的流转。
--   选它还有两个理由：
--     1) 不与三个子菜单撞脸（表单设计 Document / 流程设计 Share / 流程运行 Promotion）；
--     2) MagicStick 让位后，V59 归一化出来的 /invoice/parser「解析设置」= MagicStick
--        就成了这个图标的唯一使用者 —— 魔法棒留给「识别 / 解析」反而比放在流程目录更合理，
--        属于本次更名的附带收益，不需要额外改动。
--
-- 为什么 DB 也要改 name（不能只改前端 i18n）：
--   前端 menuName() 优先读 i18n，改了 i18n 侧边栏就显示新名；但
--   「系统设置 → 菜单管理」列表页读的是 sys_menu.name，任何仍按原始 name 匹配
--   脚本 / 排查的人看到的也还是旧名。V49__rename_todo_menu_labels 就是同样处理。
--
-- 同步改动（本次一并完成，见 frontend/src/i18n/locales/）：
--   zh-CN.ts  menu.design._            '设计开发' → '流程管理'
--              system.permModule.design '设计开发' → '流程管理'
--   en-US.ts  同上两处 → 'Flow Management'
--   注释同步：// 设计开发（P9 阶段引入）→ // 流程管理（原「设计开发」，V60 更名）
--
-- 不改的东西（有意为之）：
--   path=/design、component=design/*、权限码 design:*、前端目录 views/design/
--   全部保持原样 —— 它们是代码层面的稳定标识符，改名会牵连 sys_role_perm 绑定、
--   路由表 builtInViews 和所有 hasPerm('design:*') 调用点，收益远小于风险。
--   本次只改「显示名」与「图标」，符合 V49 的既有处理方式。
--
-- 幂等：WHERE 带旧值判定，重跑不会产生额外变更。

UPDATE sys_menu SET name = 'Flow Management', icon = 'Connection'
 WHERE id = 30
   AND name = 'Design Development'
   AND icon = 'MagicStick';

-- 自检：确认该行已落到新值（正常输出 1；0 说明库里的值与预期不符，需人工确认）
DO $$
DECLARE
    v_id    bigint;
    v_name  text;
    v_icon  text;
    v_count int;
BEGIN
    SELECT count(*) INTO v_count FROM sys_menu WHERE id = 30;
    IF v_count = 0 THEN
        RAISE NOTICE 'V60: 未找到 sys_menu id=30（设计开发 DIR），请人工确认菜单是否被改动过';
        RETURN;
    END IF;

    SELECT name, icon INTO v_name, v_icon FROM sys_menu WHERE id = 30;
    IF v_name = 'Flow Management' AND v_icon = 'Connection' THEN
        RAISE NOTICE 'V60: id=30 已为 Flow Management / Connection';
    ELSE
        RAISE WARNING 'V60: id=30 仍为 % / %，未匹配到预期旧值（name=Design Development AND icon=MagicStick），请人工确认', v_name, v_icon;
    END IF;
END $$;