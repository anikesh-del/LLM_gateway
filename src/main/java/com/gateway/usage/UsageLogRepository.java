package com.gateway.usage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {

    List<UsageLog> findByApiKeyIdOrderByCreatedAtDesc(UUID apiKeyId, Pageable pageable);

    long countByApiKeyId(UUID apiKeyId);
}

