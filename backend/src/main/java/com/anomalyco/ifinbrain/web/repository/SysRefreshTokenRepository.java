package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.SysRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SysRefreshTokenRepository extends JpaRepository<SysRefreshToken, Long> {

    Optional<SysRefreshToken> findByTokenHash(String tokenHash);

    @Query("SELECT t FROM SysRefreshToken t WHERE t.userId = :userId AND t.revoked = 0 AND t.expiresAt > :now")
    List<SysRefreshToken> findActiveByUser(Long userId, LocalDateTime now);

    @Modifying
    @Query("UPDATE SysRefreshToken t SET t.revoked = 1 WHERE t.userId = :userId")
    void revokeAllByUser(Long userId);

    @Modifying
    @Query("DELETE FROM SysRefreshToken t WHERE t.expiresAt < :cutoff OR t.revoked = 1")
    void purge(LocalDateTime cutoff);
}