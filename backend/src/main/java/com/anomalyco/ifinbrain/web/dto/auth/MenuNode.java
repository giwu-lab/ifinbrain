package com.anomalyco.ifinbrain.web.dto.auth;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class MenuNode {
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private String permission;
    private String type;
    private List<MenuNode> children = new ArrayList<>();

    public MenuNode(Long id, Long parentId, String name, String path, String component,
                    String icon, Integer sort, String permission, String type, List<MenuNode> children) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.path = path;
        this.component = component;
        this.icon = icon;
        this.sort = sort;
        this.permission = permission;
        this.type = type;
        this.children = children != null ? children : new ArrayList<>();
    }
}