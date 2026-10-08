package com.anomalyco.ifinbrain.web.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
     * 所属部门 id。null 或 0 = 不归属任何部门（落 NULL）。
     * 用 0 而不是 null 表示「清空」，与 sys_dept.parent_id=0 的约定一致 ——
     * 见 {@code UserUpdateReq.deptId}。
     */
    private Long deptId;

    private Short status;

    private List<Long> roleIds;

    /** Lombok 不再生成，手写是为了在 Bean Validation 之前 trim 掉首尾空白。 */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}