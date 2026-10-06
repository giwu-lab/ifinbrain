package com.anomalyco.ifinbrain.web.common;

import java.util.Set;

/**
 * 系统预设对象 —— 由 {@code db/migration/V2__seed.sql} 种下、随系统存在的账号与角色。
 *
 * <p>它们不是普通业务数据：{@code admin} 账号是唯一的超级管理员入口，
 * {@code admin} 角色承载全量权限。删掉任何一个，系统都会失去自我管理能力 ——
 * 既无法再登录管理后台，也无法再通过接口授予权限。
 *
 * <p>因此删除链路上必须有硬拦截（{@code UserService.delete} / {@code RoleService.delete}），
 * 拦截返回 409 Conflict 而不是 404/500：请求本身合法，冲突的是资源当前的状态。
 *
 * <p><b>前端镜像</b>：{@code views/system/user/Index.vue} 与
 * {@code views/system/role/Index.vue} 也按同样的字面量隐藏删除入口，纯属体验优化；
 * 真正的防线在后端，前端绕过不算数。
 */
public final class SystemPresets {

    private SystemPresets() {}

    /** 超级管理员用户名。 */
    public static final String ADMIN_USERNAME = "admin";

    /** 超级管理员角色编码。 */
    public static final String ADMIN_ROLE_CODE = "admin";

    /** 任务管理者角色编码 —— 跨用户任务管理，持有 todo:task:view-all。 */
    public static final String TASK_MANAGER_ROLE_CODE = "task_manager";

    /**
     * 不可删除的角色编码集合。
     *
     * <p>{@code admin} 承载全量权限，删了系统失去自我管理能力；
     * {@code task_manager} 是 V48 引入的跨用户任务管理角色，删了会让
     * 「谁能看到全员任务」这个能力无处安放。
     */
    public static final Set<String> PROTECTED_ROLE_CODES =
            Set.of(ADMIN_ROLE_CODE, TASK_MANAGER_ROLE_CODE);
}
