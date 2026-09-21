package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalDiagnosticsServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "demo");

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private AskRetrievalHitRepository retrievalHitRepository;

    private RetrievalDiagnosticsService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalDiagnosticsService(accessControlService, auditService, retrievalHitRepository);
    }

    @Test
    void returnsRankAndSimilarityOnlyForAuthorizedReader() {
        AskRetrievalHit hit = new AskRetrievalHit(
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "handbook.md",
            "chunk#2",
            0.91,
            OffsetDateTime.now()
        );
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(true);
        when(retrievalHitRepository.findByRequestId(TENANT_ID, REQUEST_ID)).thenReturn(Optional.of(
            new AskRetrievalDiagnostics(
                UUID.randomUUID(),
                REQUEST_ID,
                OffsetDateTime.now(),
                AskResultStatus.ANSWERED,
                null,
                true,
                RetrievalMode.VECTOR,
                5,
                0.35,
                List.of(hit)
            )
        ));

        RetrievalDiagnosticsResponse response = service.get(USER, REQUEST_ID);

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.topSimilarity()).isEqualTo(0.91);
        assertThat(response.topK()).isEqualTo(5);
        assertThat(response.retrievalMode()).isEqualTo(RetrievalMode.VECTOR);
        assertThat(response.hits()).singleElement().satisfies(item -> {
            assertThat(item.rank()).isEqualTo(1);
            assertThat(item.filename()).isEqualTo("handbook.md");
            assertThat(item.similarity()).isEqualTo(0.91);
        });
    }

    @Test
    void deniesOrdinaryUserAndWritesAuditEvent() {
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.get(USER, REQUEST_ID))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);

        verify(auditService).recordDenied(
            USER,
            "RETRIEVAL_DIAGNOSTIC_GET",
            "ASK_LOG",
            REQUEST_ID,
            "MISSING_AUDIT_READER"
        );
    }

    @Test
    void doesNotExposeAnotherTenantRequest() {
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(true);
        when(retrievalHitRepository.findByRequestId(TENANT_ID, REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER, REQUEST_ID))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);
    }
}
