package com.anomalyco.ifinbrain.web.datamgmt.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.datamgmt.dto.*;
import com.anomalyco.ifinbrain.web.datamgmt.entity.SysDatasource;
import com.anomalyco.ifinbrain.web.datamgmt.service.DatasourceSchemaService;
import com.anomalyco.ifinbrain.web.datamgmt.service.DatasourceService;
import com.anomalyco.ifinbrain.web.datamgmt.service.DatasourceTestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datamgmt/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceService datasourceService;
    private final DatasourceTestService testService;
    private final DatasourceSchemaService schemaService;

    @GetMapping
    public Result<List<DatasourceDto>> list() {
        return Result.ok(datasourceService.list());
    }

    @GetMapping("/{id}")
    public Result<DatasourceDto> detail(@PathVariable Long id) {
        return Result.ok(datasourceService.detail(id));
    }

    @PostMapping
    public Result<DatasourceDto> create(@Valid @RequestBody DatasourceCreateReq req) {
        return Result.ok(datasourceService.create(req));
    }

    @PutMapping("/{id}")
    public Result<DatasourceDto> update(@PathVariable Long id, @Valid @RequestBody DatasourceUpdateReq req) {
        return Result.ok(datasourceService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasourceService.delete(id);
        return Result.ok();
    }

    /** 测已存数据源（解密 → 连通 → 写回测试摘要） */
    @PostMapping("/{id}/test")
    public Result<TestResultDto> testExisting(@PathVariable Long id) {
        return Result.ok(testService.testExisting(id));
    }

    /** 测表单输入（创建/编辑时实时验证；不写库） */
    @PostMapping("/test")
    public Result<TestResultDto> testNewConfig(@RequestBody TestNewReq req) {
        SysDatasource e = SysDatasource.builder()
                .dbType(req.dbType())
                .host(req.host())
                .port(req.port())
                .databaseName(req.databaseName())
                .username(req.username())
                .build();
        return Result.ok(testService.testNewConfig(e, req.password()));
    }

    public record TestNewReq(
            String dbType,
            String host,
            Integer port,
            String databaseName,
            String username,
            String password) {}

    /** 浏览数据源 schema（tables + columns）。给前端"元数据"面板用。 */
    @GetMapping("/{id}/schema")
    public Result<DatasourceSchemaDto> schema(@PathVariable Long id) {
        return Result.ok(schemaService.fetch(id));
    }
}