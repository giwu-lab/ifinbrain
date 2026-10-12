package com.anomalyco.ifinbrain.web.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserUpdateReq {
    @Size(min = 3, max = 64)
    private String username;

    /**
     * 留空（null 或空串）= 不修改密码，由 {@code UserService.update} 的 isBlank() 判定。
     *
     * <p><b>这里不能写 {@code min}</b>：Bean Validation 在进入 service 之前就会拦下空串
     * （长度 0 &lt; min），「留空不修改」这条路径永远走不到 service，编辑用户时
     * 必然报 "password: 个数必须在6和64之间"。长度下限因此下移到 service，只对
     * 真正要设置的新密码生效。
     */
    @Size(max = 64)
    private String password;

    private String displayName;

    /**
     * 邮箱<b>选填</b>：只有真填了内容才校验格式。
     *
     * <p>{@code @Email} 本身放行 null 和空串，所以「留空」不会报错；但它不认纯空白，
     * 而 {@code "  a@b.com  "} 这种首尾带空格的输入在输入框里太容易产生。
     * setter 在<b>绑定阶段</b>做 trim——必须早于校验，因为校验先于 service 执行，
     * 等 service 再补救就已经晚了（与 password 的 min 同理，见上）。
     *
     * <p>空白串保留为 {@code ""} 而不转 null：service 用 {@code != null} 区分
     * 「本次没带这个字段（不动）」和「带了但清空（置空）」，转 null 会把清空语义吃掉。
     */
    @Email
    @Size(max = 128)
    private String email;

    private String phone;

    /**
     * 所属部门 id，<b>必填语义</b>（见 {@code UserCreateReq.deptId}）。
     *
     * <p>原来的三态是 null=不动 / 0=清空 / &gt;0=改挂；部门改为必填后，
     * 「清空」不再是合法操作，只剩两态：
     * <ul>
     *   <li>null（字段没带）= 不动，保持原部门</li>
     *   <li>&gt;0 = 改挂到该部门；部门不存在时 service 抛 400</li>
     * </ul>
     *
     * <p><b>这里刻意只写 {@code @Positive} 而不写 {@code @NotNull}</b>：
     * {@code @Positive} 对 null 放行，所以「本次不传部门」这条 partial-update
     * 路径不受影响（其它字段如 displayName / email / status 同样按 null 判定不动）；
     * 加了 {@code @NotNull} 会把每一次局部更新都打成 400。
     * 而 0 被 {@code @Positive} 拒掉，等于从协议层关闭了「清空部门归属」这条后门 ——
     * 前端也必须同步不再提交 0（见 views/system/user/Index.vue 的 save）。
     */
    @Positive
    private Long deptId;

    private Short status;

    private List<Long> roleIds;

    /** Lombok 不再生成，手写是为了在 Bean Validation 之前 trim 掉首尾空白。 */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}