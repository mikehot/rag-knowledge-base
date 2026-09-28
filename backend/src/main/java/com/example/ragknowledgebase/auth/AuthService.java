package com.example.ragknowledgebase.auth;

import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AppProperties properties;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final DefaultAccessProvisioner accessProvisioner;

    public AuthService(
        AppProperties properties,
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        DefaultAccessProvisioner accessProvisioner
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accessProvisioner = accessProvisioner;
    }

    @PostConstruct
    @Transactional
    public void ensureDefaultUser() {
        var existing = userRepository.findByUsername(properties.auth().defaultUsername());
        String defaultPassword = properties.auth().defaultPassword();
        if (existing.isEmpty() && (defaultPassword == null || defaultPassword.isBlank())) {
            return;
        }
        AppUser user = existing
            .orElseGet(() -> userRepository.save(new AppUser(
                UUID.randomUUID(),
                properties.enterprise().defaultTenantId(),
                properties.auth().defaultUsername(),
                passwordEncoder.encode(defaultPassword)
            )));
        if (properties.enterprise().initializeDefaultAccess()) {
            accessProvisioner.ensureAccess(user);
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        AppUser user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())
            || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return new LoginResponse(jwtService.createToken(user), properties.auth().tokenExpiresSeconds());
    }
}
