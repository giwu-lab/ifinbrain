package com.anomalyco.ifinbrain.web.common;

import com.anomalyco.ifinbrain.web.entity.SysPermission;
import com.anomalyco.ifinbrain.web.entity.SysRole;
import com.anomalyco.ifinbrain.web.entity.SysRolePerm;
import com.anomalyco.ifinbrain.web.repository.SysPermissionRepository;
import com.anomalyco.ifinbrain.web.repository.SysRolePermRepository;
import com.anomalyco.ifinbrain.web.repository.SysRoleRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 实体级授权的公共实现 —— 档案库（{@code documents:{slug}:{verb}}）、
 * 大屏（{@code bi:dashboard:{code}:{verb}}）、报表（{@code report:{code}:{verb}}）
 * 三套都用它。
 *
 * <p><b>为什么要抽出来</b>：档案库那套逻辑（注册 perm / seed admin /
 * 查授权 / 改授权 / admin 强制保留）约 150 行，直接复制到大屏和报表就是三份。
 * 三份各自演化，迟早出现「档案库改了另外两个没改」的权限漏洞 —— 那是最难发现
 * 的一类 bug。参数化之后，新增一种实体只要调 {@link #ensurePerms} 和
 * {@link #getPermissions}，不会再多出第三份逻辑。
 *
 * <p><b>权限码格式</b>：{@code {prefix}:{code}:{verb}}。中间段用实体的稳定
 * 业务标识（档案库 slug / 大屏 code / 报表 code），不用自增 id —— id 会随
 * 数据迁移变化，权限码变了等于所有授权一夜蒸发。
 *
 * <p><b>admin 永久保留</b>：{@link #updatePermissions} 无论如何都会把 admin
 * 角色写进每个 verb。否则管理员给别人授权时如果手滑把 admin 取消勾选，
 * 就再也没有人能改回来了（要直接进库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityAuthSupport {

    /** 实体级权限的 4 个 verb，顺序即前端授权表格的列顺序 */
    public static final List<String> VERBS = List.of("view", "design", "publish", "delete");

    private static final String ADMIN_CODE = "admin";

    /** 4 个 verb 的中文显示名，用于 sys_permission.name */
    private static final Map<String, String> VERB_ZH = Map.of(
            "view", "查看",
            "design", "编辑设计",
            "publish", "发布下线",
            "delete", "删除"
    );

    private final SysPermissionRepository permRepo;
    private final SysRolePermRepository rolePermRepo;
    private final SysRoleRepository roleRepo;
    private final PermissionChecker perm;

    /** 拼权限码：{prefix}:{code}:{verb} */
    public String permCode(String prefix, String code, String verb) {
        return prefix + ":" + code + ":" + verb;
    }

    /**
     * 从当前用户持有的权限码里，反推出他有权访问的实体 code 集合。
     *
     * <p><b>为什么要「全量取一次」而不是逐行 has</b>：列表要真分页。逐行
     * {@code has} 意味着先把整表拉进内存过滤，分页就名存实亡了。这里从
     * JWT 里已有的 perm 集合反推 code 列表，再用 {@code In} 把过滤下推给数据库
     * —— 这正是 {@link PermissionChecker#currentPerms()} 注释里写的用法。
     *
     * @param prefix 权限码前缀，如 {@code bi:dashboard}
     * @param verb   实体级 verb，如 {@code view}
     */
    public Set<String> allowedCodes(String prefix, String verb) {
        String head = prefix + ":";
        String tail = ":" + verb;
        Set<String> out = new java.util.HashSet<>();
        for (String p : perm.currentPerms()) {
            if (!p.startsWith(head) || !p.endsWith(tail)) continue;
            int end = p.length() - tail.length();
            // 中间段为空 = 这就是**模块级**码本身（如 bi:dashboard:view 恰好也满足
            // 前缀 bi:dashboard: 和后缀 :view）。必须先判长度再做 substring ——
            // 否则 end < head.length()，substring 会抛 StringIndexOutOfBounds，
            // 整个列表接口 500。
            if (end <= head.length()) continue;
            String code = p.substring(head.length(), end);
            // code 里含冒号说明不是本模块生成的实体级码，别误认
            if (!code.contains(":")) out.add(code);
        }
        return out;
    }

    /** 当前用户对某个实体是否有权做某动作 */
    public boolean has(String prefix, String code, String verb) {
        return perm.has(permCode(prefix, code, verb));
    }

    /**
     * 实体级鉴权，失败时把权限码写进消息 —— 排查时能直接看到缺哪个码。
     *
     * <p>与模块级 {@code perm.require} 语义不同：这里只判实体级。调用方要
     * 「两层都要过」时，自己先 {@code perm.require(moduleCode)} 再调本方法。
     */
    public void require(String prefix, String code, String verb) {
        if (!has(prefix, code, verb)) {
            throw new BizException(403, "无权访问该" + entityZh(prefix) + "（" + permCode(prefix, code, verb) + "）");
        }
    }

    /** 注册 4 个 perm code（已存在则跳过，幂等）。新建实体时调用。 */
    @Transactional
    public void ensurePerms(String prefix, String code, String entityName) {
        for (String verb : VERBS) {
            String pc = permCode(prefix, code, verb);
            if (!permRepo.existsByCode(pc)) {
                permRepo.save(SysPermission.builder()
                        .code(pc)
                        .name(entityName + " " + VERB_ZH.get(verb))
                        .type("API")
                        .build());
            }
        }
    }

    /**
     * 给 admin 角色 seed 4 个 perm。其他角色一律不 seed ——
     * 「新建了不等于所有人都能看到」，这是实体级授权的全部意义。
     */
    @Transactional
    public void seedAdmin(String prefix, String code) {
        SysRole admin = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"));
        for (String verb : VERBS) {
            Optional<SysPermission> p = permRepo.findByCode(permCode(prefix, code, verb));
            if (p.isEmpty()) continue;
            Long permId = p.get().getId();
            boolean bound = rolePermRepo.findAll().stream()
                    .anyMatch(rp -> rp.getRoleId().equals(admin.getId()) && rp.getPermId().equals(permId));
            if (!bound) {
                rolePermRepo.save(SysRolePerm.builder().roleId(admin.getId()).permId(permId).build());
            }
        }
        rolePermRepo.flush();
    }

    /** 新建实体 = 注册 perm + seed admin，一步到位 */
    @Transactional
    public void onEntityCreated(String prefix, String code, String entityName) {
        ensurePerms(prefix, code, entityName);
        seedAdmin(prefix, code);
    }

    /**
     * 查当前授权：{verb: [roleId]}，供前端授权对话框预填。
     * 某个 perm 还没注册时返回空列表而不是抛错 —— 对话框要能打开，
     * 否则权限码缺失会变成「授权按钮点不动」且看不出原因。
     */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> getPermissions(String prefix, String code) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (String verb : VERBS) {
            Optional<SysPermission> p = permRepo.findByCode(permCode(prefix, code, verb));
            if (p.isEmpty()) {
                result.put(verb, List.of());
                continue;
            }
            result.put(verb, rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.get().getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList());
        }
        return result;
    }

    /**
     * 覆盖式写入授权：入参 {@code {verb: [roleId]}}，空列表 = 收回全部（admin 除外）。
     */
    @Transactional
    public void updatePermissions(String prefix, String code, Map<String, List<Long>> grants) {
        Long adminId = roleRepo.findByCode(ADMIN_CODE)
                .orElseThrow(() -> new BizException(500, "admin 角色缺失"))
                .getId();

        for (String verb : VERBS) {
            String pc = permCode(prefix, code, verb);
            SysPermission p = permRepo.findByCode(pc)
                    .orElseThrow(() -> new BizException(500, "perm 缺失: " + pc));

            List<Long> requested = grants == null ? List.of() : grants.getOrDefault(verb, List.of());
            List<Long> finalRoleIds = new ArrayList<>(requested);
            if (!finalRoleIds.contains(adminId)) finalRoleIds.add(adminId);

            List<Long> currentRoleIds = rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(p.getId()))
                    .map(SysRolePerm::getRoleId)
                    .toList();

            for (Long rid : finalRoleIds) {
                if (!currentRoleIds.contains(rid)) {
                    rolePermRepo.save(SysRolePerm.builder().roleId(rid).permId(p.getId()).build());
                }
            }
            for (Long rid : currentRoleIds) {
                if (!finalRoleIds.contains(rid)) {
                    rolePermRepo.deleteByRoleIdAndPermId(rid, p.getId());
                }
            }
        }
        rolePermRepo.flush();
    }

    /**
     * 实体删除后清理它的 4 个权限码与全部角色绑定。
     *
     * <p>不清理会留下「幽灵授权」：code 允许复用（删一张报表再建同 code 的），
     * 新实体创建时 {@link #ensurePerms} 发现 perm 已存在就跳过，于是白捡一份
     * 上一任的授权 —— 新报表一建出来就已经可见给上一批角色了。
     */
    @Transactional
    public void revokeEntity(String prefix, String code) {
        for (String verb : VERBS) {
            Optional<SysPermission> p = permRepo.findByCode(permCode(prefix, code, verb));
            if (p.isEmpty()) continue;
            Long permId = p.get().getId();
            rolePermRepo.deleteAll(rolePermRepo.findAll().stream()
                    .filter(rp -> rp.getPermId().equals(permId))
                    .toList());
            permRepo.delete(p.get());
        }
        rolePermRepo.flush();
        permRepo.flush();
    }

    /** 实体中文名，仅用于 403 消息里的人话 */
    private static String entityZh(String prefix) {
        return switch (prefix) {
            case "documents" -> "档案库";
            case "bi:dashboard" -> "大屏";
            case "report" -> "报表";
            default -> "资源";
        };
    }
}