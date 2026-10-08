package com.anomalyco.ifinbrain.web.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 四象限的一个格子：key + 该象限的任务列表。
 *
 * <p>四个格子**恒定全部返回**（含空列表），前端不必处理"某格缺失"的分支，
 * 也就不会出现某个象限因为 0 条而在 grid 里塌掉一格的情况。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoQuadrantBucket {

    /** Q1 / Q2 / Q3 / Q4。 */
    private String key;
    /** 该象限的任务数（= tasks.size()，冗余一份让前端不必先 length 才敢判断是否为空）。 */
    private long count;
    /** 该象限内的任务，已按「逾期优先 → 截止时间升序」排好。 */
    private List<TodoTaskDto> tasks;
}
