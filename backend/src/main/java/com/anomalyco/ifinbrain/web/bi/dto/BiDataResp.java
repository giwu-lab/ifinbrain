package com.anomalyco.ifinbrain.web.bi.dto;

import com.anomalyco.ifinbrain.web.datamgmt.dto.QueryResultDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大屏取数结果。
 *
 * <p>结构与 {@link QueryResultDto} 对齐（columns[].key/type + rows[][]），
 * 多两个缓存标记：{@code cached} 告诉播放页这帧是不是缓存的，{@code cacheAgeMs} 让它
 * 在「数据很久没更新」时能提示一句。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BiDataResp {

    /** 列元数据 [{key,label,type}]；复用 QueryResultDto.Column，不重复定义一套 */
    private List<QueryResultDto.Column> columns;

    /** 数据行；每行一个 List，顺序对齐 columns */
    private List<List<Object>> rows;

    private int rowCount;

    /** 是否被行数截断（超过 10000 行） */
    private boolean truncated;

    /** 本次取数耗时（毫秒）；命中缓存时是「上一次真实执行」的耗时 */
    private long durationMs;

    /** true = 命中服务端 TTL 缓存 */
    private boolean cached;

    /** 命中缓存时，距上次真实执行的毫秒数；未命中为 0 */
    private long cacheAgeMs;

    /** 缓存命中的副本：同一份数据 + 缓存标记 */
    public BiDataResp asCached(long ageMs) {
        return BiDataResp.builder()
                .columns(columns)
                .rows(rows)
                .rowCount(rowCount)
                .truncated(truncated)
                .durationMs(durationMs)
                .cached(true)
                .cacheAgeMs(ageMs)
                .build();
    }
}
