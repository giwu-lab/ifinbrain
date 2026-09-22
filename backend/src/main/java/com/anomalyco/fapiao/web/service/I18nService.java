package com.anomalyco.fapiao.web.service;

import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.dto.i18n.I18nImportResult;
import com.anomalyco.fapiao.web.dto.i18n.I18nMessageDto;
import com.anomalyco.fapiao.web.dto.i18n.I18nUpsertRequest;
import com.anomalyco.fapiao.web.entity.SysI18nMessage;
import com.anomalyco.fapiao.web.repository.SysI18nMessageRepository;
import com.anomalyco.fapiao.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class I18nService {

    private final SysI18nMessageRepository repo;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    /** 全量快照：返回 {locale: nestedMessages}，与 vue-i18n messages 格式兼容 */
    public Map<String, Map<String, Object>> listAllByLocale() {
        perm.require("i18n:view");
        List<SysI18nMessage> all = repo.findAll();
        // locale → 嵌套 map
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (SysI18nMessage m : all) {
            Map<String, String> byLocale = parseValueJson(m.getValueJson());
            for (var e : byLocale.entrySet()) {
                Map<String, Object> nested = out.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                setNested(nested, m.getNamespace(), m.getCode(), e.getValue());
            }
        }
        return out;
    }

    public List<I18nMessageDto> listByNamespace(String namespace) {
        perm.require("i18n:view");
        return repo.findByNamespaceOrderByCodeAsc(namespace)
                .stream().map(I18nMessageDto::from).toList();
    }

    public List<String> listNamespaces() {
        perm.require("i18n:view");
        return repo.listNamespaces();
    }

    public Map<String, Long> countByNamespace() {
        perm.require("i18n:view");
        return repo.findAll().stream()
                .collect(Collectors.groupingBy(SysI18nMessage::getNamespace, Collectors.counting()));
    }

    // ===================== 写入 =====================

    @Transactional
    public I18nMessageDto upsert(String namespace, String code, String valueJson) {
        perm.require("i18n:update");
        validateNamespace(namespace);
        validateCode(code);
        validateValueJson(valueJson);
        SysI18nMessage m = repo.findByNamespaceAndCode(namespace, code)
                .orElseGet(() -> SysI18nMessage.builder()
                        .namespace(namespace)
                        .code(code)
                        .build());
        m.setValueJson(valueJson);
        m.setUpdatedAt(LocalDateTime.now());
        if (m.getId() == null) m.setCreatedAt(LocalDateTime.now());
        return I18nMessageDto.from(repo.save(m));
    }

    @Transactional
    public void delete(String namespace, String code) {
        perm.require("i18n:delete");
        SysI18nMessage m = repo.findByNamespaceAndCode(namespace, code)
                .orElseThrow(() -> new BizException(404, "翻译项不存在"));
        repo.delete(m);
    }

    @Transactional
    public void resetNamespace(String namespace) {
        perm.require("i18n:delete");
        validateNamespace(namespace);
        long n = repo.deleteByNamespace(namespace);
        log.info("i18n reset namespace={} deleted={}", namespace, n);
    }

    // ===================== 导出 / 导入 =====================

    /** 导出全部 i18n 记录为 JSON 字符串。
     * 格式：[{"namespace":"menu","items":[{"code":"invoice.List","valueJson":"{\"zh-CN\":\"...\",\"en-US\":\"...\"}"}]}] */
    @Transactional(readOnly = true)
    public String exportAll() {
        perm.require("i18n:view");
        List<SysI18nMessage> all = repo.findAll();
        Map<String, List<SysI18nMessage>> grouped = all.stream()
                .collect(Collectors.groupingBy(SysI18nMessage::getNamespace,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            Map<String, Object> typeEntry = new LinkedHashMap<>();
            typeEntry.put("namespace", e.getKey());
            List<Map<String, Object>> items = e.getValue().stream()
                    .sorted(Comparator.comparing(SysI18nMessage::getCode))
                    .map(m -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("code", m.getCode());
                        x.put("valueJson", m.getValueJson());
                        return x;
                    }).toList();
            typeEntry.put("items", items);
            out.add(typeEntry);
        }
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(out);
        } catch (Exception e) {
            throw new BizException(500, "序列化失败: " + e.getMessage());
        }
    }

    /** 批量导入。mode = "merge"（存在则更新）或 "replace"（先删后插）。
     * 返回 inserted / updated / skipped / errors。 */
    @Transactional
    public I18nImportResult importFile(MultipartFile file, String mode) {
        perm.require("i18n:update");
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
        if (!filename.endsWith(".json")) {
            throw new BizException(400, "仅支持 .json");
        }
        String modeNorm = (mode == null || mode.isBlank()) ? "merge" : mode.toLowerCase();
        if (!modeNorm.equals("merge") && !modeNorm.equals("replace")) {
            throw new BizException(400, "mode 仅支持 merge / replace");
        }

        List<Map<String, Object>> data;
        try {
            byte[] bytes = file.getBytes();
            // 去 BOM
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parsed = (List<Map<String, Object>>) (List<?>)
                    new tools.jackson.databind.ObjectMapper().readValue(text, List.class);
            data = parsed;
        } catch (Exception e) {
            throw new BizException(400, "JSON 解析失败: " + e.getMessage());
        }

        I18nImportResult result = I18nImportResult.builder().build();
        if ("replace".equals(modeNorm)) {
            // 先清空
            for (String ns : repo.listNamespaces()) {
                repo.deleteByNamespace(ns);
            }
        }

        for (var entry : data) {
            String ns = str(entry.get("namespace"));
            if (ns == null || ns.isBlank()) {
                result.addError("跳过：缺少 namespace"); result.skipped++; continue;
            }
            try {
                validateNamespace(ns);
            } catch (Exception e) {
                result.addError("跳过：namespace '" + ns + "' 不合法"); result.skipped++; continue;
            }
            Object itemsRaw = entry.get("items");
            if (!(itemsRaw instanceof List<?> itemList)) {
                result.addError("跳过：namespace '" + ns + "' 缺少 items");
                continue;
            }
            for (Object itemObj : itemList) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    result.addError("跳过：[" + ns + "] 条目格式错误"); result.skipped++; continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemMap;
                String code = str(item.get("code"));
                String valueJson = str(item.get("valueJson"));
                if (code == null || code.isBlank() || valueJson == null || valueJson.isBlank()) {
                    result.addError("跳过：[" + ns + "] 条目缺少 code/valueJson"); result.skipped++; continue;
                }
                try {
                    validateCode(code);
                    validateValueJson(valueJson);
                    boolean exists = repo.findByNamespaceAndCode(ns, code).isPresent();
                    if (exists) {
                        upsert(ns, code, valueJson);
                        result.updated++;
                    } else {
                        upsert(ns, code, valueJson);
                        result.inserted++;
                    }
                } catch (Exception e) {
                    result.addError("[" + ns + "/" + code + "] " + e.getMessage());
                }
            }
        }
        return result;
    }

    // ===================== 同步 ts 文件默认值 =====================

    /**
     * 一键同步：把 classpath:i18n/defaults.json（由 frontend/scripts/i18n-export.mjs 生成）
     * 全部 upsert 到 sys_i18n_message 表，mode=merge。已存在的 key 会被覆盖为默认值。
     *
     * 适用场景：ts 文件改了之后一键回写到 DB；或首次部署自动初始化。
     */
    @Transactional
    public I18nImportResult syncFromDefaults() {
        perm.require("i18n:update");
        org.springframework.core.io.ClassPathResource res =
                new org.springframework.core.io.ClassPathResource("i18n/defaults.json");
        byte[] bytes;
        try {
            bytes = res.getInputStream().readAllBytes();
        } catch (Exception e) {
            throw new BizException(500, "无法读取 defaults.json: " + e.getMessage());
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) (List<?>)
                new tools.jackson.databind.ObjectMapper().readValue(text, List.class);

        I18nImportResult result = I18nImportResult.builder().build();
        for (var entry : data) {
            String ns = str(entry.get("namespace"));
            if (ns == null || ns.isBlank()) continue;
            try { validateNamespace(ns); } catch (Exception e) { result.addError("跳过：namespace '" + ns + "' 不合法"); continue; }
            Object itemsRaw = entry.get("items");
            if (!(itemsRaw instanceof List<?> itemList)) continue;
            for (Object itemObj : itemList) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    result.addError("跳过：[" + ns + "] 条目格式错误"); result.skipped++; continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemMap;
                String code = str(item.get("code"));
                String valueJson = str(item.get("valueJson"));
                if (code == null || code.isBlank() || valueJson == null || valueJson.isBlank()) {
                    result.addError("跳过：[" + ns + "] 条目缺少 code/valueJson"); result.skipped++; continue;
                }
                try {
                    validateCode(code);
                    validateValueJson(valueJson);
                    boolean exists = repo.findByNamespaceAndCode(ns, code).isPresent();
                    upsert(ns, code, valueJson);
                    if (exists) result.updated++; else result.inserted++;
                } catch (Exception e) {
                    result.addError("[" + ns + "/" + code + "] " + e.getMessage());
                }
            }
        }
        log.info("[i18n] syncFromDefaults: inserted={}, updated={}, skipped={}",
                result.inserted, result.updated, result.skipped);
        return result;
    }

    // ===================== 工具方法 =====================

    private static String str(Object o) {
        if (o == null) return null;
        return o.toString().trim();
    }

    /** valueJson 是 JSON object：{"zh-CN": "...", "en-US": "..."} */
    private static Map<String, String> parseValueJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) out.put(k, v.toString());
            });
            return out;
        } catch (Exception e) {
            log.warn("parseValueJson failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 把 {namespace: {code: value}} 嵌套写入。code 可能含 .，例如 "invoice.List" → invoice.List 是第二层 key */
    @SuppressWarnings("unchecked")
    private static void setNested(Map<String, Object> root, String namespace, String code, String value) {
        Object nsObj = root.computeIfAbsent(namespace, k -> new LinkedHashMap<String, Object>());
        Map<String, Object> nsMap = (Map<String, Object>) nsObj;
        // code 可能是 'a.b'，按 . 分层；最后一段作为 leaf
        String[] segs = code.split("\\.");
        Map<String, Object> cur = nsMap;
        for (int i = 0; i < segs.length - 1; i++) {
            Object next = cur.get(segs[i]);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> n = new LinkedHashMap<>();
                cur.put(segs[i], n);
                cur = n;
            } else {
                cur = (Map<String, Object>) next;
            }
        }
        cur.put(segs[segs.length - 1], value);
    }

    private static void validateNamespace(String ns) {
        if (ns == null || ns.isBlank()) throw new BizException(400, "namespace 不能为空");
        if (!ns.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new BizException(400, "namespace 必须小写字母开头，仅含 a-z / 0-9 / _");
        }
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank()) throw new BizException(400, "code 不能为空");
        if (code.length() > 128) throw new BizException(400, "code 过长");
    }

    private static void validateValueJson(String json) {
        if (json == null || json.isBlank()) throw new BizException(400, "valueJson 不能为空");
        try {
            new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new BizException(400, "valueJson 不是合法 JSON object: " + e.getMessage());
        }
    }
}