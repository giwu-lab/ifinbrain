package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.dto.setting.ParserConfigDto;
import com.anomalyco.ifinbrain.web.dto.setting.StorageConfigDto;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system/settings")
@RequiredArgsConstructor
public class SettingController {

    private final SettingService settingService;
    private final PermissionChecker perm;

    @GetMapping("/storage")
    public Result<StorageConfigDto> storage() {
        perm.require("setting:view");
        return Result.ok(settingService.getStorageConfig());
    }

    @PutMapping("/storage")
    public Result<Void> saveStorage(@RequestBody StorageConfigDto dto) {
        perm.require("setting:update");
        settingService.saveStorageConfig(dto);
        return Result.ok();
    }

    /** 测试 S3 连通：传 form 上的覆盖值（未填字段回落到已存配置）。 */
    @PostMapping("/storage/test")
    public Result<Map<String, Object>> testStorage(@RequestBody(required = false) StorageConfigDto dto) {
        perm.require("setting:view");
        return Result.ok(settingService.testStorageConnection(dto));
    }

    /** 解析 API（通用 OCR/发票解析服务）的配置入口。 */
    @GetMapping("/parser")
    public Result<ParserConfigDto> parser() {
        perm.require("parser:view");
        return Result.ok(settingService.getParserConfig());
    }

    @PutMapping("/parser")
    public Result<Void> saveParser(@RequestBody ParserConfigDto dto) {
        perm.require("parser:update");
        settingService.saveParserConfig(dto);
        return Result.ok();
    }

    @PostMapping("/parser/test")
    public Result<Map<String, Object>> testParser(@RequestBody(required = false) ParserConfigDto dto) {
        perm.require("parser:view");
        return Result.ok(settingService.testParserConnection(dto));
    }
}