package com.example.ragknowledgebase.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsername(String username);

    boolean existsByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);
}
