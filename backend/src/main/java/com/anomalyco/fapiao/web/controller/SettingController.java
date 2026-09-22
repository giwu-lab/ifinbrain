package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.dto.setting.ParserConfigDto;
import com.anomalyco.fapiao.web.dto.setting.StorageConfigDto;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import com.anomalyco.fapiao.web.service.SettingService;
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

    /** 解析 API（通用 OCR/发票解析服务）的配置入口。 */
    @GetMapping("/parser")
    public Result<ParserConfigDto> parser() {
        perm.require("ocr:view");
        return Result.ok(settingService.getParserConfig());
    }

    @PutMapping("/parser")
    public Result<Void> saveParser(@RequestBody ParserConfigDto dto) {
        perm.require("ocr:update");
        settingService.saveParserConfig(dto);
        return Result.ok();
    }

    @PostMapping("/parser/test")
    public Result<Map<String, Object>> testParser(@RequestBody(required = false) ParserConfigDto dto) {
        perm.require("ocr:view");
        return Result.ok(settingService.testParserConnection(dto));
    }
}