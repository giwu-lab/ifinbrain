package com.anomalyco.ifinbrain.web.design.portal.compiler;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.design.portal.entity.SysPortalBlock;
import com.anomalyco.ifinbrain.web.design.portal.repository.SysPortalBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * layout_schema 服务端 IR 校验器。
 *
 * 思想来源：企业级动态数据模型平台文章 §四「把 Schema 编译成可执行的运行时」。
 * 我们把 layoutSchema JSON 当作"源语言"，在落库前编译为 IR，
 * 校验所有节点 type 合法、id 唯一、无环 —— 杜绝前端绕过属性面板写入无效 JSON。
 *
 * 设计取舍（MVP）：
 *   - 不引入 networknt/json-schema-validator 等第三方（多 200KB+ 依赖）
 *   - 自写最小校验器：遍历 + 节点级校验 + 路径化报错
 *   - 后续如需联动 / 条件显示等高级语义，再扩展 IR 节点字段
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LayoutSchemaValidator {

    /** 根节点 type 固定为 'page' —— 整个布局的逻辑根 */
    public static final String ROOT_TYPE = "page";

    /** 容器 type —— 可嵌套 children */
    public static final String TYPE_ROW = "row";

    private final SysPortalBlockRepository blockRepo;
    private final ObjectMapper mapper;

    /**
     * 校验并规范化 JSON 字符串。成功返回原 JSON（已规范化），失败抛 BizException(400)。
     */
    public String validate(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new BizException(400, "layoutSchema 不能为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(schemaJson);
        } catch (Exception e) {
            throw new BizException(400, "layoutSchema 不是合法 JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new BizException(400, "layoutSchema 根必须是对象");
        }
        if (!root.has("root")) {
            throw new BizException(400, "layoutSchema 必须包含 root 节点");
        }
        JsonNode rootNode = root.get("root");
        if (!ROOT_TYPE.equals(textOf(rootNode, "type"))) {
            throw new BizException(400, "layoutSchema.root.type 必须是 '" + ROOT_TYPE + "'");
        }

        // 加载已注册的 block type 集合（built_in + 用户扩展），用于校验 children type
        Set<String> registered = new HashSet<>();
        for (SysPortalBlock b : blockRepo.findAllByOrderByCategoryAscSortAscIdAsc()) {
            if (b.getEnabled() != null && b.getEnabled() == 1) {
                registered.add(b.getType());
            }
        }
        registered.add(ROOT_TYPE);
        registered.add(TYPE_ROW);
        registered.add("col");

        // 深度遍历：校验每个节点 + 收集所有 id（检查重复）
        Map<String, Integer> idCount = new HashMap<>();
        walk(rootNode, "$", registered, idCount);

        // id 唯一性
        for (var e : idCount.entrySet()) {
            if (e.getValue() > 1) {
                throw new BizException(400, "节点 id 重复: " + e.getKey()
                        + "（出现 " + e.getValue() + " 次）");
            }
        }

        // 输出规范化 JSON（重新写一遍，确保字段顺序与默认值一致）
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BizException(500, "layoutSchema 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 递归遍历；path 用 JSONPath 风格（便于报错时定位）。
     */
    private void walk(JsonNode node, String path, Set<String> registered, Map<String, Integer> idCount) {
        if (node == null || node.isNull()) return;

        // 节点类型校验
        String type = textOf(node, "type");
        if (type == null || type.isBlank()) {
            throw new BizException(400, path + ".type 不能为空");
        }
        if (!registered.contains(type)) {
            throw new BizException(400, path + ".type='" + type + "' 未注册或已禁用");
        }

        // id 校验（root 节点允许 id='root'）
        if (node.has("id") && !node.get("id").isNull()) {
            String id = node.get("id").asText();
            if (id.isBlank()) {
                throw new BizException(400, path + ".id 不能为空");
            }
            idCount.merge(id, 1, Integer::sum);
        }

        // 容器节点递归 children
        if (node.has("children") && !node.get("children").isNull()) {
            JsonNode children = node.get("children");
            if (!children.isArray()) {
                throw new BizException(400, path + ".children 必须是数组");
            }
            int i = 0;
            for (Iterator<JsonNode> it = ((ArrayNode) children).elements().iterator(); it.hasNext(); ) {
                JsonNode child = it.next();
                walk(child, path + ".children[" + i + "]", registered, idCount);
                i++;
            }
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}