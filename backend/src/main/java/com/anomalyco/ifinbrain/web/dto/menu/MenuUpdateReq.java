package com.anomalyco.ifinbrain.web.dto.menu;

import com.anomalyco.ifinbrain.web.entity.SysMenu;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MenuUpdateReq {

    private Long parentId;

    @Size(max = 64)
    private String name;

    @Size(max = 255)
    private String path;

    @Size(max = 128)
    private String component;

    @Size(max = 64)
    private String permission;

    @Size(max = 32)
    private String icon;

    @Size(max = 16)
    private String type;

    private Integer sort;

    private Short status;

    private Long queryId;

    @Size(max = 8192)
    private String configJson;

    /** 把非 null 字段 copy 到 entity（partial update） */
    public void applyTo(SysMenu m) {
        if (parentId != null) m.setParentId(parentId);
        if (name != null) m.setName(name);
        if (path != null) m.setPath(path);
        if (component != null) m.setComponent(component);
        if (permission != null) m.setPermission(permission);
        if (icon != null) m.setIcon(icon);
        if (type != null) m.setType(type);
        if (sort != null) m.setSort(sort);
        if (status != null) m.setStatus(status);
        if (queryId != null) m.setQueryId(queryId);
        if (configJson != null) m.setConfigJson(configJson);
    }
}