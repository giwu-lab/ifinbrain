package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TodoTagRepository extends JpaRepository<TodoTag, Long> {

    List<TodoTag> findByOwnerIdOrderByNameAsc(Long ownerId);

    Optional<TodoTag> findByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndName(Long ownerId, String name);
}
