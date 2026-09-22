package com.anomalyco.fapiao.web.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserUpdateReq {
    @Size(min = 3, max = 64)
    private String username;

    @Size(min = 6, max = 64)
    private String password;

    private String displayName;

    @Email
    private String email;

    private String phone;

    private Short status;

    private List<Long> roleIds;
}