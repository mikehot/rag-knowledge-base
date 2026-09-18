package com.example.ragknowledgebase.config;

import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.auth.JwtAuthenticationFilter;
import com.example.ragknowledgebase.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        AccessControlService accessControlService,
        ObjectMapper objectMapper
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> writeError(
                    response,
                    objectMapper,
                    401,
                    "登录已失效，请重新登录"
                ))
                .accessDeniedHandler((request, response, accessDeniedException) -> writeError(
                    response,
                    objectMapper,
                    403,
                    "没有权限访问该资源"
                )))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
                .access((authentication, context) -> {
                    Object principal = authentication.get().getPrincipal();
                    boolean allowed = principal instanceof AuthenticatedUser user
                        && accessControlService.canReadAuditEvents(user);
                    return new AuthorizationDecision(allowed);
                })
                .requestMatchers(
                    "/api/auth/login",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/livez",
                    "/readyz"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    private void writeError(
        jakarta.servlet.http.HttpServletResponse response,
        ObjectMapper objectMapper,
        int code,
        String message
    ) throws java.io.IOException {
        response.setStatus(code == 401 ? 401 : 403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
