package com.anomalyco.fapiao.web.controller;

import com.anomalyco.fapiao.web.common.Result;
import com.anomalyco.fapiao.web.dto.setting.OcrConfigDto;
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

    @GetMapping("/ocr")
    public Result<OcrConfigDto> ocr() {
        perm.require("ocr:view");
        return Result.ok(settingService.getOcrConfig());
    }

    @PutMapping("/ocr")
    public Result<Void> saveOcr(@RequestBody OcrConfigDto dto) {
        perm.require("ocr:update");
        settingService.saveOcrConfig(dto);
        return Result.ok();
    }

    @PostMapping("/ocr/test")
    public Result<Map<String, Object>> testOcr(@RequestBody(required = false) OcrConfigDto dto) {
        perm.require("ocr:view");
        return Result.ok(settingService.testOcrConnection(dto));
    }
}