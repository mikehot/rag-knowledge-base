package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
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

    @DeleteMapping("/{id}")
    public ApiResponse<DeleteDocumentResponse> delete(Authentication authentication, @PathVariable UUID id) {
        return ApiResponse.ok(documentService.delete(currentUser(authentication), id));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
