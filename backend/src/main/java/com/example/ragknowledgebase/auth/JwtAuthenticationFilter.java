package com.example.ragknowledgebase.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AppUserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            // A valid signature is not enough: the user must still exist, be ACTIVE and belong to the tenant.
            jwtService.parse(header.substring(7))
                .filter(user -> userRepository.existsByIdAndTenantIdAndStatus(user.userId(), user.tenantId(), "ACTIVE"))
                .ifPresent(user -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        java.util.List.of()
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        }
        filterChain.doFilter(request, response);
    }
}
