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

    @PostMapping("/users/{userId}/roles")
    public ApiResponse<UserResponse> assignUserRole(
        Authentication authentication,
        @PathVariable UUID userId,
        @Valid @RequestBody AssignUserRoleRequest request
    ) {
        return ApiResponse.ok(adminService.assignUserRole(currentUser(authentication), userId, request));
    }

    @DeleteMapping("/users/{userId}/roles/{roleCode}")
    public ApiResponse<DeleteUserRoleResponse> revokeUserRole(
        Authentication authentication,
        @PathVariable UUID userId,
        @PathVariable String roleCode
    ) {
        return ApiResponse.ok(adminService.revokeUserRole(currentUser(authentication), userId, roleCode));
    }

    @GetMapping("/departments")
    public ApiResponse<List<DepartmentResponse>> departments(Authentication authentication) {
        return ApiResponse.ok(adminService.listDepartments(currentUser(authentication)));
    }

    @PostMapping("/departments")
    public ApiResponse<DepartmentResponse> createDepartment(
        Authentication authentication,
        @Valid @RequestBody CreateDepartmentRequest request
    ) {
        return ApiResponse.ok(adminService.createDepartment(currentUser(authentication), request));
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<List<KnowledgeBaseResponse>> knowledgeBases(Authentication authentication) {
        return ApiResponse.ok(adminService.listKnowledgeBases(currentUser(authentication)));
    }

    @PostMapping("/knowledge-bases")
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(
        Authentication authentication,
        @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.ok(adminService.createKnowledgeBase(currentUser(authentication), request));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/disable")
    public ApiResponse<KnowledgeBaseResponse> disableKnowledgeBase(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId
    ) {
        return ApiResponse.ok(adminService.updateKnowledgeBaseStatus(
            currentUser(authentication),
            knowledgeBaseId,
            "DISABLED"
        ));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/activate")
    public ApiResponse<KnowledgeBaseResponse> activateKnowledgeBase(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId
    ) {
        return ApiResponse.ok(adminService.updateKnowledgeBaseStatus(
            currentUser(authentication),
            knowledgeBaseId,
            "ACTIVE"
        ));
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
