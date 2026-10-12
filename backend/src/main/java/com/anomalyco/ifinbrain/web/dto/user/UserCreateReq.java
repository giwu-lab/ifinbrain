package com.anomalyco.ifinbrain.web.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserCreateReq {
    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Size(min = 6, max = 64)
    private String password;

    private String displayName;

    /**
     * 邮箱选填：留空不校验，填了才要求是合法地址。
     * 口径与 {@code UserUpdateReq.email} 一致（含 setter 里的 trim）。
     */
    @Email
    @Size(max = 128)
    private String email;

    private String phone;

    /**
     * 所属部门 id，<b>必填</b>。
     *
     * <p>两个注解分工明确：{@code @NotNull} 拦「没传字段」（连 0 都不给），
     * {@code @Positive} 拦「传了 0 或负数」——0 是本项目 {@code sys_dept.parent_id=0}
     * 约定里的「根节点」占位值，落到 {@code sys_user.dept_id} 上只会造出一个指向
     * 空气的用户。两者缺一，缺 {@code @Positive} 时 0 会静默通过并被 service
     * 归一成 NULL，等于绕过必填。
     *
     * <p>对比 {@code UserUpdateReq.deptId}：那里<b>不能</b>加 {@code @NotNull}，
     * 否则会破坏 partial-update 契约（null = 本次不改这个字段）。
     */
    @NotNull
    @Positive
    private Long deptId;

    private Short status;

    private List<Long> roleIds;

    /** Lombok 不再生成，手写是为了在 Bean Validation 之前 trim 掉首尾空白。 */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}