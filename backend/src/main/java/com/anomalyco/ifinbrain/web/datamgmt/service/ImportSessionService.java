package com.anomalyco.ifinbrain.web.datamgmt.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导入预检会话：把「解析结果」固定下来，供 validate / commit 反复读取。
 *
 * <p><b>为什么需要会话</b>：预检与执行必须基于同一份解析结果。如果 commit 时重新上传，
 * 用户完全可能在两步之间改了文件、换了编码、换了个 sheet —— 预检结论就完全作废了，
 * 用户看到的「0 个错误」和真正写进去的东西对不上。所以上传一次解析，之后都靠 sessionId 走。
 *
 * <p><b>为什么放内存而不是临时文件</b>：v1 的行数上限是 5 万行 / 20MB，
 * 解析后大概几十 MB 量级，内存够用且省掉一套文件生命周期管理。
 * 要支持更大文件时（Phase 2）只需把 {@link #rows} 换成从临时文件读，
 * 对外的接口签名完全不变。
 *
 * <p><b>容量闸门</b>：全局条目数 + 单会话行数双限制。否则「50 个人同时各传一个
 * 5 万行文件」就能把堆撑爆 —— 限额不是防手滑，是防并发叠加。
 */
@Slf4j
@Service
public class ImportSessionService {

    /** 会话内不可变的解析产物 + 目标信息 */
    @Getter
    public static class Session {
        private final String id;
        private final Long datasourceId;
        private final String schema;
        private final String table;
        private final String fileName;
        private final long fileSize;
        private final ImportFileReader.Kind kind;
        private final String sheetName;
        private final String encoding;
        private final boolean hasHeader;
        private final List<String> headers;
        private final List<List<String>> rows;
        private volatile Instant lastAccess;

        Session(String id, Long datasourceId, String schema, String table,
                String fileName, long fileSize, ImportFileReader.Kind kind,
                String sheetName, String encoding, boolean hasHeader,
                List<String> headers, List<List<String>> rows) {
            this.id = id;
            this.datasourceId = datasourceId;
            this.schema = schema;
            this.table = table;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.kind = kind;
            this.sheetName = sheetName;
            this.encoding = encoding;
            this.hasHeader = hasHeader;
            this.headers = headers;
            this.rows = rows;
            this.lastAccess = Instant.now();
        }

        /**
         * 第 i 行数据在源文件里的物理行号（1 基）。
         *
         * <p>有表头时数据从第 2 行开始，所以偏移 1；无表头时偏移 0。
         * 错误提示里的行号必须和用户在 Excel 里看到的对得上 ——
         * 报「第 1 行」而实际是第 2 行，用户会先去查表头。
         */
        public int physicalRowNo(int dataIndex0) {
            return dataIndex0 + (hasHeader ? 2 : 1);
        }

        void touch() {
            this.lastAccess = Instant.now();
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Value("${ifinbrain.import.session-ttl:30m}")
    private Duration ttl;

    @Value("${ifinbrain.import.max-sessions:20}")
    private int maxSessions;

    @PostConstruct
    void init() {
        log.info("[import] session store ready (ttl={}, maxSessions={})", ttl, maxSessions);
    }

    /**
     * 登记一个新会话。会超出容量时先清掉最久没访问的 —— 宁可让某个老会话提前过期，
     * 也不要因为「20 个人同时导入」把后来者的上传直接拒掉。
     */
    public Session create(Long datasourceId, String schema, String table,
                          MultipartFile file,
                          ImportFileReader.ParsedFile parsed,
                          boolean hasHeader) {

        evictExpired();
        if (sessions.size() >= maxSessions) {
            evictOldest(1);
        }

        String id = UUID.randomUUID().toString();
        Session s = new Session(
                id,
                datasourceId,
                schema,
                table,
                file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
                file.getSize(),
                parsed.kind(),
                parsed.usedSheet(),
                parsed.detectedEncoding(),
                hasHeader,
                parsed.headers(),
                parsed.rows());

        sessions.put(id, s);
        log.info("[import] session created id={} ds={} {}.{} file={} rows={} headers={} truncated={}",
                id, datasourceId, schema, table, s.getFileName(),
                parsed.rows().size(), parsed.headers().size(), parsed.truncated());
        return s;
    }

    /**
     * 取会话并顺带续期。
     *
     * <p>过期即 400（而不是静默重建）：用户看到「会话已过期，请重新选择文件」，
     * 远比看到一个空文件列表然后不知道为什么少了几列要好排查。
     */
    public Session require(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(400, "缺少导入会话，请重新选择文件");
        }
        Session s = sessions.get(sessionId);
        if (s == null) {
            throw new BizException(400, "导入会话不存在或已过期，请重新选择文件");
        }
        if (isExpired(s)) {
            sessions.remove(sessionId, s);
            throw new BizException(400, "导入会话已过期（超过 " + ttl.toMinutes() + " 分钟），请重新选择文件");
        }
        s.touch();
        return s;
    }

    /** 用户改了目标表 / 换文件时主动丢弃，避免无用会话占满容量 */
    public void discard(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    /** 每 5 分钟清一次过期会话 */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void evictExpired() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> isExpired(e.getValue()));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("[import] evicted {} expired session(s), {} alive", removed, sessions.size());
        }
    }

    private void evictOldest(int count) {
        sessions.entrySet().stream()
                .sorted((a, b) -> a.getValue().getLastAccess().compareTo(b.getValue().getLastAccess()))
                .limit(count)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(sessions::remove);
    }

    private boolean isExpired(Session s) {
        return Duration.between(s.getLastAccess(), Instant.now()).compareTo(ttl) > 0;
    }

    public int size() {
        return sessions.size();
    }

    @PreDestroy
    void shutdown() {
        sessions.clear();
        log.info("[import] session store cleared on shutdown");
    }
}