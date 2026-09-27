package com.anomalyco.ifinbrain.web.dto.menu;

import com.anomalyco.ifinbrain.web.entity.SysMenu;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MenuCreateReq {

    /** 父菜单 id；顶层 DIR 传 0 */
    private Long parentId;

    @NotBlank
    @Size(max = 64)
    private String name;

    /** 路由 path；顶层 DIR 可空 */
    @Size(max = 255)
    private String path;

    /** 组件 key（与前端 builtInViews 对应）；DIR 不传 */
    @Size(max = 128)
    private String component;

    /** 权限码；用于路由守卫 */
    @Size(max = 64)
    private String permission;

    /** Element Plus icon 名 */
    @Size(max = 32)
    private String icon;

    /** DIR / MENU */
    @NotBlank
    @Size(max = 16)
    private String type;

    /** 排序；默认 99 */
    private Integer sort;

    /** 启用 / 禁用 */
    private Short status;

    /** query-page 发布专用：关联 query id；其他场景可不传 */
    private Long queryId;

    /** query-page 发布专用：配置 JSON（字符串） */
    @Size(max = 8192)
    private String configJson;

    public SysMenu toEntity() {
        SysMenu m = new SysMenu();
        m.setParentId(parentId == null ? 0L : parentId);
        m.setName(name);
        m.setPath(path);
        m.setComponent(component);
        m.setPermission(permission);
        m.setIcon(icon);
        m.setType(type);
        m.setSort(sort == null ? 99 : sort);
        m.setStatus(status == null ? (short) 1 : status);
        m.setQueryId(queryId);
        m.setConfigJson(configJson);
        return m;
    }
}