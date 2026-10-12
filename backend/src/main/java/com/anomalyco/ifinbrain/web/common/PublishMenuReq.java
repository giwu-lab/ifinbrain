package com.anomalyco.ifinbrain.web.common;

import lombok.Data;

/**
 * 「发布到菜单目录」的请求体 —— 报表与大屏共用一份。
 *
 * <p>两个模块的发布语义完全一致（选目录 → 生成侧边栏入口 → 按 report:view /
 * bi:dashboard:view 控角色可见性），各写一套 DTO 只会带来「报表能选目录、
 * 大屏不能」这种不一致，所以合并。
 *
 * <p><b>所有字段都可留空</b>：留空 = 沿用上次的配置。重新发布时后端只覆盖
 * 请求里真正带了的字段，否则用户调好的目录和显示名会被无声地弹回默认值。
 */
@Data
public class PublishMenuReq {

    /** 挂到哪个目录下（sys_menu.type='DIR' 的 id）。不传 = 上次的，首次则 /bi */
    private Long parentId;

    /** 侧边栏显示名。不传 = 上次的，首次则业务对象名 */
    private String menuName;

    /** 菜单图标（Element Plus 图标组件名）。不传 = 上次的，首次则 'Tickets' */
    private String icon;

    /** 排序。不传 = 上次的，首次则 99 */
    private Integer sort;
}
