package com.example.ragknowledgebase.admin;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleResponse>> roles(Authentication authentication) {
        return ApiResponse.ok(adminService.listRoles(currentUser(authentication)));
    }

    @GetMapping("/users")
    public ApiResponse<List<UserResponse>> users(Authentication authentication) {
        return ApiResponse.ok(adminService.listUsers(currentUser(authentication)));
    }

    @PostMapping("/users")
    public ApiResponse<UserResponse> createUser(
        Authentication authentication,
        @Valid @RequestBody CreateUserRequest request
    ) {
        return ApiResponse.ok(adminService.createUser(currentUser(authentication), request));
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<List<KnowledgeBaseResponse>> knowledgeBases(Authentication authentication) {
        return ApiResponse.ok(adminService.listKnowledgeBases(currentUser(authentication)));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/memberships")
    public ApiResponse<List<KnowledgeBaseMembershipResponse>> memberships(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId
    ) {
        return ApiResponse.ok(adminService.listMemberships(currentUser(authentication), knowledgeBaseId));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/memberships")
    public ApiResponse<KnowledgeBaseMembershipResponse> grantMembership(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId,
        @Valid @RequestBody GrantKnowledgeBaseMembershipRequest request
    ) {
        return ApiResponse.ok(adminService.grantMembership(currentUser(authentication), knowledgeBaseId, request));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/memberships/{membershipId}")
    public ApiResponse<DeleteMembershipResponse> revokeMembership(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId,
        @PathVariable UUID membershipId
    ) {
        return ApiResponse.ok(adminService.revokeMembership(currentUser(authentication), knowledgeBaseId, membershipId));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
