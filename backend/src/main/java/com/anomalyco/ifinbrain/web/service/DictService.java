package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.dto.dict.DictImportResult;
import com.anomalyco.ifinbrain.web.dto.dict.DictItemDto;
import com.anomalyco.ifinbrain.web.dto.dict.DictTypeDto;
import com.anomalyco.ifinbrain.web.entity.SysDictItem;
import com.anomalyco.ifinbrain.web.entity.SysDictType;
import com.anomalyco.ifinbrain.web.repository.SysDictItemRepository;
import com.anomalyco.ifinbrain.web.repository.SysDictTypeRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DictService {

    private final SysDictTypeRepository typeRepo;
    private final SysDictItemRepository itemRepo;
    private final PermissionChecker perm;

    // ===================== 类型 =====================

    public List<DictTypeDto> listAllTypes() {
        perm.require("dict:view");
        List<SysDictType> types = typeRepo.findAllByOrderByIdAsc();
        Map<Long, Long> counts = itemRepo.findAll().stream()
                .collect(Collectors.groupingBy(SysDictItem::getDictId, Collectors.counting()));
        return types.stream()
                .map(t -> DictTypeDto.from(t, counts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Transactional
    public DictTypeDto createType(String code, String name, String remark, Short status) {
        perm.require("dict:update");
        validateCode(code);
        if (typeRepo.existsByCode(code)) {
            throw new BizException(400, "字典编码已存在: " + code);
        }
        SysDictType t = SysDictType.builder()
                .code(code.trim())
                .name(name == null ? "" : name.trim())
                .remark(remark)
                .status(status == null ? 1 : status)
                .build();
        t = typeRepo.save(t);
        return DictTypeDto.from(t, 0L);
    }

    @Transactional
    public DictTypeDto updateType(Long id, String name, String remark, Short status) {
        perm.require("dict:update");
        SysDictType t = typeRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "字典类型不存在"));
        if (name != null) t.setName(name.trim());
        if (remark != null) t.setRemark(remark);
        if (status != null) t.setStatus(status);
        t = typeRepo.save(t);
        long cnt = itemRepo.findByDictIdOrderBySortAscIdAsc(id).size();
        return DictTypeDto.from(t, cnt);
    }

    @Transactional
    public void deleteType(Long id) {
        perm.require("dict:delete");
        SysDictType t = typeRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "字典类型不存在"));
        // 子表 ON DELETE CASCADE 自动清条目
        typeRepo.delete(t);
    }

    // ===================== 条目 =====================

    public List<DictItemDto> listItemsByTypeCode(String code) {
        perm.require("dict:view");
        SysDictType t = typeRepo.findByCode(code)
                .orElseThrow(() -> new BizException(404, "字典类型不存在: " + code));
        return itemRepo.findByDictIdOrderBySortAscIdAsc(t.getId())
                .stream().map(DictItemDto::from).toList();
    }

    @Transactional
    public DictItemDto createItem(Long dictId, String code, String label, String value,
                                  Long parentId, Integer sort, Short status) {
        perm.require("dict:update");
        if (!typeRepo.existsById(dictId)) {
            throw new BizException(404, "字典类型不存在");
        }
        if (itemRepo.existsByDictIdAndCode(dictId, code)) {
            throw new BizException(400, "该字典下条目编码已存在: " + code);
        }
        SysDictItem it = SysDictItem.builder()
                .dictId(dictId)
                .code(code.trim())
                .label(label == null ? "" : label.trim())
                .value(value)
                .parentId(parentId == null ? 0L : parentId)
                .sort(sort == null ? 0 : sort)
                .status(status == null ? 1 : status)
                .build();
        return DictItemDto.from(itemRepo.save(it));
    }

    @Transactional
    public DictItemDto updateItem(Long id, String label, String value, Long parentId,
                                  Integer sort, Short status) {
        perm.require("dict:update");
        SysDictItem it = itemRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "字典条目不存在"));
        if (label != null) it.setLabel(label.trim());
        if (value != null) it.setValue(value);
        if (parentId != null) it.setParentId(parentId);
        if (sort != null) it.setSort(sort);
        if (status != null) it.setStatus(status);
        return DictItemDto.from(itemRepo.save(it));
    }

    @Transactional
    public void deleteItem(Long id) {
        perm.require("dict:delete");
        SysDictItem it = itemRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "字典条目不存在"));
        itemRepo.delete(it);
    }

    // ===================== 全量缓存 =====================

    /**
     * 全量快照，供前端缓存使用：{ code -> [items] }
     */
    public Map<String, List<DictItemDto>> cache() {
        perm.require("dict:view");
        List<SysDictType> types = typeRepo.findAllByOrderByIdAsc();
        Map<Long, String> idToCode = types.stream()
                .collect(Collectors.toMap(SysDictType::getId, SysDictType::getCode));
        Map<Long, List<DictItemDto>> byId = itemRepo.findAll().stream()
                .collect(Collectors.groupingBy(
                        SysDictItem::getDictId,
                        Collectors.mapping(DictItemDto::from, Collectors.toList())));
        Map<String, List<DictItemDto>> out = new LinkedHashMap<>();
        for (var t : types) {
            List<DictItemDto> items = byId.getOrDefault(t.getId(), List.of());
            items = items.stream()
                    .sorted(Comparator.comparing(DictItemDto::getSort,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            out.put(t.getCode(), items);
        }
        // 防御性 fallback：避免 idToCode 空引用
        if (idToCode != null) { /* noop */ }
        return out;
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(400, "字典编码不能为空");
        }
        if (!code.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new BizException(400, "字典编码必须小写字母开头，仅含 a-z / 0-9 / _");
        }
    }

    // ===================== 导出 / 导入 =====================

    /** 导出全库字典为 JSON 字符串 */
    @Transactional(readOnly = true)
    public String exportAll() {
        perm.require("dict:view");
        // 顺序：types → items[]（按 typeCode, item.sort）
        List<SysDictType> types = typeRepo.findAllByOrderByIdAsc();
        Map<Long, List<SysDictItem>> byId = itemRepo.findAll().stream()
                .collect(Collectors.groupingBy(SysDictItem::getDictId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var t : types) {
            Map<String, Object> typeEntry = new LinkedHashMap<>();
            typeEntry.put("code", t.getCode());
            typeEntry.put("name", t.getName());
            typeEntry.put("remark", t.getRemark());
            typeEntry.put("status", t.getStatus());
            List<Map<String, Object>> items = byId.getOrDefault(t.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(SysDictItem::getSort,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(it -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", it.getCode());
                        m.put("label", it.getLabel());
                        m.put("value", it.getValue());
                        m.put("parentId", it.getParentId());
                        m.put("sort", it.getSort());
                        m.put("status", it.getStatus());
                        return m;
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

    /**
     * 批量导入：解析上传文件（JSON / CSV），按 typeCode 创建类型，按 (typeCode, itemCode) upsert 条目。
     * dryRun=true 时只解析不写库，返回可导入数量；false 时真正写入。
     */
    @Transactional
    public DictImportResult importFile(MultipartFile file, boolean dryRun) {
        perm.require("dict:update");
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
        List<Map<String, Object>> typesData;
        try {
            byte[] bytes = file.getBytes();
            if (filename.endsWith(".json")) {
                typesData = parseJson(bytes);
            } else if (filename.endsWith(".csv")) {
                typesData = parseCsv(bytes);
            } else {
                throw new BizException(400, "仅支持 .json / .csv");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "文件解析失败: " + e.getMessage());
        }

        int ok = 0, updated = 0, skipped = 0, err = 0;
        List<String> messages = new ArrayList<>();
        for (var t : typesData) {
            String typeCode = str(t.get("code"));
            if (typeCode == null || typeCode.isBlank()) {
                messages.add("跳过：缺少字典编码");
                err++; continue;
            }
            SysDictType type;
            try {
                type = typeRepo.findByCode(typeCode).orElseGet(() -> {
                    if (dryRun) {
                        SysDictType stub = SysDictType.builder().code(typeCode).build();
                        return stub;
                    }
                    SysDictType t2 = SysDictType.builder()
                            .code(typeCode)
                            .name(strOr(t.get("name"), typeCode))
                            .remark(str(t.get("remark")))
                            .status(t.get("status") == null ? (short) 1 : ((Number) t.get("status")).shortValue())
                            .build();
                    return typeRepo.save(t2);
                });
                if (!dryRun && type.getName() == null) type.setName(strOr(t.get("name"), typeCode));
            } catch (Exception e) {
                messages.add("类型 [" + typeCode + "] 失败: " + e.getMessage());
                err++; continue;
            }

            Object itemsRaw = t.get("items");
            if (!(itemsRaw instanceof List<?> itemList)) continue;
            for (Object itemObj : itemList) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    messages.add("跳过：条目格式错误");
                    skipped++; continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemMap;
                String itemCode = str(item.get("code"));
                if (itemCode == null || itemCode.isBlank()) {
                    messages.add("跳过：条目 [" + typeCode + "] 缺少 code");
                    skipped++; continue;
                }
                try {
                    boolean exists = itemRepo.existsByDictIdAndCode(type.getId(), itemCode);
                    if (dryRun) {
                        if (exists) updated++; else ok++;
                    } else {
                        if (exists) {
                            // 更新已有条目
                            SysDictItem old = itemRepo.findByDictIdOrderBySortAscIdAsc(type.getId())
                                    .stream().filter(i -> i.getCode().equals(itemCode)).findFirst().orElseThrow();
                            if (item.get("label") != null) old.setLabel(strOr(item.get("label"), itemCode));
                            if (item.get("value") != null) old.setValue(str(item.get("value")));
                            if (item.get("parentId") != null) old.setParentId(((Number) item.get("parentId")).longValue());
                            if (item.get("sort") != null) old.setSort(((Number) item.get("sort")).intValue());
                            if (item.get("status") != null) old.setStatus(((Number) item.get("status")).shortValue());
                            itemRepo.save(old);
                            updated++;
                        } else {
                            SysDictItem ni = SysDictItem.builder()
                                    .dictId(type.getId())
                                    .code(itemCode)
                                    .label(strOr(item.get("label"), itemCode))
                                    .value(str(item.get("value")))
                                    .parentId(item.get("parentId") == null ? 0L : ((Number) item.get("parentId")).longValue())
                                    .sort(item.get("sort") == null ? 0 : ((Number) item.get("sort")).intValue())
                                    .status(item.get("status") == null ? 1 : ((Number) item.get("status")).shortValue())
                                    .build();
                            itemRepo.save(ni);
                            ok++;
                        }
                    }
                } catch (Exception e) {
                    messages.add("条目 [" + typeCode + "/" + itemCode + "] 失败: " + e.getMessage());
                    err++;
                }
            }
        }
        return DictImportResult.builder()
                .dryRun(dryRun)
                .ok(ok).updated(updated).skipped(skipped).error(err)
                .messages(messages)
                .build();
    }

    private static String str(Object o) {
        if (o == null) return null;
        return o.toString().trim();
    }

    private static String strOr(Object o, String fallback) {
        String s = str(o);
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseJson(byte[] bytes) throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        return (List<Map<String, Object>>) (List<?>) mapper.readValue(bytes, List.class);
    }

    /**
     * CSV 格式：先读第一行获取 type code/name/remark/status，后续行每行一个条目
     * 列：type_code,type_name,type_remark,type_status,item_code,label,value,parent_id,sort,status
     * 不带表头也行：前 4 列是类型字段，后 6 列是条目字段
     */
    private List<Map<String, Object>> parseCsv(byte[] bytes) throws Exception {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // 去 BOM
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0) throw new BizException(400, "CSV 文件为空");

        // 检测表头
        boolean hasHeader = lines[0].toLowerCase().contains("type_code");
        int start = hasHeader ? 1 : 0;
        // 类型按出现顺序合并：先出现的 type_code 一条类型
        Map<String, Map<String, Object>> typeMap = new LinkedHashMap<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            String[] cols = parseCsvLine(line);
            if (cols.length < 4) {
                throw new BizException(400, "第 " + (i + 1) + " 行列数不足");
            }
            String typeCode = cols[0].trim();
            if (typeCode.isEmpty()) continue;
            Map<String, Object> t = typeMap.computeIfAbsent(typeCode, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", k);
                return m;
            });
            if (cols.length >= 2 && !cols[1].isBlank()) t.put("name", cols[1].trim());
            if (cols.length >= 3 && !cols[2].isBlank()) t.put("remark", cols[2].trim());
            if (cols.length >= 4 && !cols[3].isBlank()) t.put("status", Short.parseShort(cols[3].trim()));
            // 条目列：item_code,label,value,parent_id,sort,status
            if (cols.length >= 10) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) t.computeIfAbsent("items", k -> new ArrayList<>());
                if (!cols[4].isBlank()) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("code", cols[4].trim());
                    if (!cols[5].isBlank()) it.put("label", cols[5].trim());
                    if (!cols[6].isBlank()) it.put("value", cols[6].trim());
                    if (!cols[7].isBlank()) it.put("parentId", Long.parseLong(cols[7].trim()));
                    if (!cols[8].isBlank()) it.put("sort", Integer.parseInt(cols[8].trim()));
                    if (!cols[9].isBlank()) it.put("status", Short.parseShort(cols[9].trim()));
                    items.add(it);
                }
            }
        }
        return new ArrayList<>(typeMap.values());
    }

    /** 简易 CSV 行解析：支持双引号包裹 + 转义双引号 */
    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else {
                        inQuote = false;
                    }
                } else cur.append(c);
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"') {
                    inQuote = true;
                } else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}