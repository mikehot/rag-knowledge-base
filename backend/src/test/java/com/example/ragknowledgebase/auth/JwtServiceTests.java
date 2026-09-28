package com.example.ragknowledgebase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.config.AppProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JwtService jwtService = new JwtService(properties());

    @Test
    void preservesUserAndTenantBoundaryInToken() {
        String token = jwtService.createToken(new AppUser(USER_ID, TENANT_ID, "demo", "hash"));

        assertThat(jwtService.parse(token))
            .contains(new AuthenticatedUser(USER_ID, TENANT_ID, "demo"));
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.createToken(new AppUser(USER_ID, TENANT_ID, "demo", "hash"));

        assertThat(jwtService.parse(token + "tampered")).isEmpty();
    }

    @Test
    void refusesToStartWithoutConfiguredSecret() {
        assertThatThrownBy(() -> new JwtService(properties("")))
            .isInstanceOf(IllegalStateException.class);
    }

    private AppProperties properties() {
        return properties("test-only-secret-that-is-long-enough-for-hs384-signing-key-material");
    }

    private AppProperties properties(String secret) {
        return new AppProperties(
            new AppProperties.Auth(
                "demo",
                "demo123456",
                secret,
                3600
            ),
            null,
            null,
            new AppProperties.Enterprise(TENANT_ID, UUID.randomUUID(), false),
            null,
            null
        );
    }
}
