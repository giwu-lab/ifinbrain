package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("SELECT u FROM SysUser u WHERE u.username LIKE %:kw% OR u.displayName LIKE %:kw%")
    List<SysUser> searchByKeyword(String kw);

    /** 任务清单执行人选择器数据源：仅启用用户，按 username 升序。 */
    List<SysUser> findAllByStatusOrderByUsernameAsc(Short status);

    /** 某部门下的人数 —— 删部门前判断是否被引用（避免留下悬空 dept_id）。 */
    long countByDeptId(Long deptId);
}