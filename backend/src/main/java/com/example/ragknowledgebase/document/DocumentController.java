package com.example.ragknowledgebase.document;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documentService;
    private final DocumentAclService documentAclService;

    public DocumentController(DocumentService documentService, DocumentAclService documentAclService) {
        this.documentService = documentService;
        this.documentAclService = documentAclService;
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentUploadResponse> upload(
        Authentication authentication,
        @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(documentService.upload(currentUser(authentication), file));
    }

    @GetMapping
    public ApiResponse<DocumentListResponse> list(Authentication authentication) {
        return ApiResponse.ok(documentService.list(currentUser(authentication)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.get(currentUser(authentication), id));
    }

    @GetMapping("/{id}/acl")
    public ApiResponse<List<DocumentAclResponse>> listAcl(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentAclService.list(currentUser(authentication), id));
    }

    @PostMapping("/{id}/acl")
    public ApiResponse<DocumentAclResponse> grantAcl(
        Authentication authentication,
        @PathVariable UUID id,
        @Valid @RequestBody GrantDocumentAclRequest request
    ) {
        return ApiResponse.ok(documentAclService.grant(currentUser(authentication), id, request));
    }

    @DeleteMapping("/{id}/acl/{aclId}")
    public ApiResponse<DeleteDocumentAclResponse> revokeAcl(
        Authentication authentication,
        @PathVariable UUID id,
        @PathVariable UUID aclId
    ) {
        return ApiResponse.ok(documentAclService.revoke(currentUser(authentication), id, aclId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<DeleteDocumentResponse> delete(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.delete(currentUser(authentication), id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<DocumentLifecycleResponse> disable(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.disable(currentUser(authentication), id));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<DocumentLifecycleResponse> enable(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.enable(currentUser(authentication), id));
    }

    @PostMapping("/{id}/reindex")
    public ApiResponse<DocumentLifecycleResponse> reindex(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.reindex(currentUser(authentication), id));
    }

    @PostMapping("/{id}/replace")
    public ApiResponse<DocumentReplaceResponse> replace(
        Authentication authentication,
        @PathVariable UUID id,
        @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(documentService.replace(currentUser(authentication), id, file));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
