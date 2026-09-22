package com.anomalyco.fapiao.web.repository;

import com.anomalyco.fapiao.web.entity.SysI18nMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysI18nMessageRepository extends JpaRepository<SysI18nMessage, Long> {

    List<SysI18nMessage> findByNamespaceOrderByCodeAsc(String namespace);

    List<SysI18nMessage> findByCodeOrderByNamespaceAsc(String code);

    Optional<SysI18nMessage> findByNamespaceAndCode(String namespace, String code);

    long countByNamespace(String namespace);

    @Query("select distinct m.namespace from SysI18nMessage m order by m.namespace")
    List<String> listNamespaces();

    long deleteByNamespace(String namespace);

    long deleteByNamespaceAndCode(String namespace, String code);
}