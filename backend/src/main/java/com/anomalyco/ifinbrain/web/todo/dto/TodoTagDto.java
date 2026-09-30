package com.anomalyco.ifinbrain.web.todo.dto;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoTagDto {

    private Long id;
    private Long ownerId;
    private String name;
    private LocalDateTime createdAt;

    public static TodoTagDto fromEntity(TodoTag e) {
        return TodoTagDto.builder()
                .id(e.getId())
                .ownerId(e.getOwnerId())
                .name(e.getName())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
