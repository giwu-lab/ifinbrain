package com.anomalyco.fapiao.web.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "sys_role_perm")
@IdClass(SysRolePerm.PK.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysRolePerm {

    @Id
    @Column(name = "role_id")
    private Long roleId;

    @Id
    @Column(name = "perm_id")
    private Long permId;

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class PK implements Serializable {
        private Long roleId;
        private Long permId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(roleId, pk.roleId) && Objects.equals(permId, pk.permId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permId);
        }
    }
}