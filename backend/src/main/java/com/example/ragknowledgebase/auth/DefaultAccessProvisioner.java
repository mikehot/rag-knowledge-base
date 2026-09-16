package com.example.ragknowledgebase.auth;

import com.example.ragknowledgebase.config.AppProperties;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefaultAccessProvisioner {
    private static final UUID SYSTEM_ADMIN_ROLE_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000011");

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;

    public DefaultAccessProvisioner(JdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void ensureAccess(AppUser user) {
        jdbcTemplate.update(
            """
                INSERT INTO user_role (user_id, role_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
            user.getId(),
            SYSTEM_ADMIN_ROLE_ID
        );
        jdbcTemplate.update(
            """
                INSERT INTO knowledge_base_membership (
                    id, tenant_id, knowledge_base_id, principal_type, principal_id, permission
                )
                VALUES (?, ?, ?, 'USER', ?, 'MANAGE')
                ON CONFLICT DO NOTHING
                """,
            UUID.randomUUID(),
            user.getTenantId(),
            properties.enterprise().defaultKnowledgeBaseId(),
            user.getId()
        );
    }
}
