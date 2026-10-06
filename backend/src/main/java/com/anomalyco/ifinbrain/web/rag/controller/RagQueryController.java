package com.anomalyco.ifinbrain.web.rag.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.rag.dto.RagAskReq;
import com.anomalyco.ifinbrain.web.rag.dto.RagAskResp;
import com.anomalyco.ifinbrain.web.rag.service.RagQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案库问答接口。
 *
 * <p>不单独 require 某个 perm —— 权限收敛在 {@link RagQueryService#ask} 内按
 * 「用户拥有 documents:{slug}:view 的档案库集合」圈定检索范围。有权答的自然能问，
 * 一个库都无权则直接 403。提前 require 一个全局 perm 反而会挡住「只有单库权限」的合法用户。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ifinbrain.rag", name = "enabled", havingValue = "true")
public class RagQueryController {

    private final RagQueryService service;

    @PostMapping("/ask")
    public Result<RagAskResp> ask(@Valid @RequestBody RagAskReq req) {
        var a = service.ask(req.getQuestion());
        return Result.ok(new RagAskResp(a.answer(), a.citations(), a.elapsedMs()));
    }
}
