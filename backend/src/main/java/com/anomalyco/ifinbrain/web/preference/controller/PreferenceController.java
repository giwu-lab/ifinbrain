package com.anomalyco.ifinbrain.web.preference.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.preference.dto.UserPreferenceDto;
import com.anomalyco.ifinbrain.web.preference.dto.UserPreferenceUpdateReq;
import com.anomalyco.ifinbrain.web.preference.service.PreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preference")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService service;

    @GetMapping
    public Result<UserPreferenceDto> get() {
        return Result.ok(service.get());
    }

    @PutMapping
    public Result<UserPreferenceDto> update(@Valid @RequestBody UserPreferenceUpdateReq req) {
        return Result.ok(service.upsert(req.themeJson()));
    }

    @DeleteMapping
    public Result<Void> reset() {
        service.reset();
        return Result.ok();
    }
}