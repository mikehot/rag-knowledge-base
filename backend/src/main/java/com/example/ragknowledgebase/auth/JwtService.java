package com.example.ragknowledgebase.auth;

import com.example.ragknowledgebase.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final AppProperties properties;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.auth().tokenExpiresSeconds());
        return Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact();
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            UUID userId = UUID.fromString(claims.get("uid", String.class));
            return Optional.of(new AuthenticatedUser(userId, claims.getSubject()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
