package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceCreateReq;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceDto;
import com.anomalyco.ifinbrain.web.datamgmt.dto.DatasourceUpdateReq;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.anomalyco.ifinbrain.web.datamgmt.repository.SysDatasourceRepository;
import com.anomalyco.ifinbrain.web.security.CryptoUtil;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private static final String PWD_MASK = "********";

    private final SysDatasourceRepository repo;
    private final CryptoUtil crypto;
    private final PermissionChecker perm;
    private final DatasourceConnectionService connectionService;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public List<DatasourceDto> list() {
        perm.require("data:datasource:view");
        return repo.findAllByOrderByIdAsc().stream()
                .map(this::toDtoWithStatus)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatasourceDto detail(Long id) {
        perm.require("data:datasource:view");
        SysDatasource e = repo.findById(id).orElseThrow(() -> new BizException(404, "数据源不存在"));
        return toDtoWithStatus(e);
    }

    /** 把实体转 DTO，同时探测密码密文是否可解 → passwordStatus */
    private DatasourceDto toDtoWithStatus(SysDatasource e) {
        String stored = e.getPasswordEnc();
        if (stored == null || stored.isEmpty()) {
            return DatasourceDto.fromEntity(e, "missing", "");
        }
        try {
            crypto.decrypt(stored);
            return DatasourceDto.fromEntity(e, "ok", PWD_MASK);
        } catch (Exception ex) {
            log.warn("Datasource id={} password cannot be decrypted (likely master-key rotated): {}", e.getId(), ex.getMessage());
            return DatasourceDto.fromEntity(e, "failed", "");
        }
    }

    // ===================== 创建 =====================

    @Transactional
    public DatasourceDto create(DatasourceCreateReq req) {
        perm.require("data:datasource:update");
        String name = req.getName().trim();
        if (repo.existsByName(name)) {
            throw new BizException(400, "数据源名称已存在: " + name);
        }
        SysDatasource e = SysDatasource.builder()
                .name(name)
                .dbType(req.getDbType())
                .host(req.getHost().trim())
                .port(req.getPort())
                .databaseName(req.getDatabaseName().trim())
                .username(req.getUsername().trim())
                .passwordEnc(crypto.encrypt(req.getPassword()))   // 明文 → 密文
                .remark(req.getRemark())
                .status(req.getStatus() == null ? 1 : req.getStatus())
                .build();
        e = repo.save(e);
        return toDtoWithStatus(e);
    }

    // ===================== 更新 =====================

    @Transactional
    public DatasourceDto update(Long id, DatasourceUpdateReq req) {
        perm.require("data:datasource:update");
        SysDatasource e = repo.findById(id).orElseThrow(() -> new BizException(404, "数据源不存在"));

        if (req.getName() != null) {
            String name = req.getName().trim();
            if (!name.equals(e.getName()) && repo.existsByName(name)) {
                throw new BizException(400, "数据源名称已存在: " + name);
            }
            e.setName(name);
        }
        if (req.getDbType() != null)   e.setDbType(req.getDbType());
        if (req.getHost() != null)     e.setHost(req.getHost().trim());
        if (req.getPort() != null)     e.setPort(req.getPort());
        if (req.getDatabaseName() != null) e.setDatabaseName(req.getDatabaseName().trim());
        if (req.getUsername() != null) e.setUsername(req.getUsername().trim());
        if (req.getRemark() != null)   e.setRemark(req.getRemark());
        if (req.getStatus() != null)   e.setStatus(req.getStatus());

        // 密码字段：空串 / "********" 视为「保留旧值」；否则覆盖密文
        String pwd = req.getPassword();
        boolean passwordChanged = pwd != null && !pwd.isEmpty() && !PWD_MASK.equals(pwd);
        if (passwordChanged) {
            e.setPasswordEnc(crypto.encrypt(pwd));
        }

        e = repo.save(e);

        // 配置变更 → 关旧池，下一次 getDataSource 按新配置重建
        if (passwordChanged || req.getHost() != null || req.getPort() != null
                || req.getDatabaseName() != null || req.getUsername() != null
                || req.getDbType() != null) {
            connectionService.evict(id);
        }
        return toDtoWithStatus(e);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("data:datasource:delete");
        SysDatasource e = repo.findById(id).orElseThrow(() -> new BizException(404, "数据源不存在"));
        repo.delete(e);
        connectionService.evict(id);
    }

    // ===================== 探测 / 工具 =====================

    /**
     * 给 DatasourceTestService 用：从 DB 读密文 → 解密。
     * 解密失败抛 BizException(500)，与 SettingService.getParserConfig 的探测语义一致。
     * 返回明文密码用于实际建连。
     */
    @Transactional(readOnly = true)
    public DecryptedDatasource loadDecrypted(Long id) {
        perm.require("data:datasource:test");
        SysDatasource e = repo.findById(id).orElseThrow(() -> new BizException(404, "数据源不存在"));
        try {
            String plain = crypto.decrypt(e.getPasswordEnc());
            return new DecryptedDatasource(e, plain);
        } catch (Exception ex) {
            throw new BizException(500, "数据源密码无法解密（密钥可能已轮换）");
        }
    }

    /** 给 DatasourceTestService.testNewConfig 用 */
    @Transactional(readOnly = true)
    public void requireUpdatePerm() {
        perm.require("data:datasource:update");
    }

    /** 测试器返回结构：entity + 解密后的明文密码 */
    public record DecryptedDatasource(SysDatasource entity, String plainPassword) {}

    /**
     * 测试结束后写回测试结果摘要
     */
    @Transactional
    public void recordTestResult(Long id, boolean ok, String msg) {
        SysDatasource e = repo.findById(id).orElse(null);
        if (e == null) return;
        e.setLastTestOk((short) (ok ? 1 : 0));
        e.setLastTestAt(LocalDateTime.now());
        e.setLastTestMsg(msg == null ? null : (msg.length() > 250 ? msg.substring(0, 250) : msg));
        repo.save(e);
    }
}