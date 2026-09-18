package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskFeedbackServiceTests {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID ASK_LOG_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "demo");

    @Mock
    private AskLogRepository askLogRepository;

    @Mock
    private AskFeedbackRepository askFeedbackRepository;

    @Mock
    private OperationalMetrics operationalMetrics;

    private AskFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new AskFeedbackService(askLogRepository, askFeedbackRepository, operationalMetrics);
    }

    @Test
    void createsFeedbackForOwnedAskAndSanitizesReason() {
        persistReturnsArgument();
        when(askLogRepository.findByRequestIdAndTenantIdAndUserId(REQUEST_ID, TENANT_ID, USER_ID))
            .thenReturn(Optional.of(askLog()));
        when(askFeedbackRepository.findByAskLogId(ASK_LOG_ID)).thenReturn(Optional.empty());

        AskFeedbackResponse response = service.submit(
            USER,
            REQUEST_ID,
            new AskFeedbackRequest(AskFeedbackRating.HELPFUL, "  回答\n很清楚\u200B  ")
        );

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.rating()).isEqualTo(AskFeedbackRating.HELPFUL);
        assertThat(response.reason()).isEqualTo("回答 很清楚");
        assertThat(response.updatedAt()).isNotNull();
        verify(operationalMetrics).recordFeedback(AskFeedbackRating.HELPFUL);
    }

    @Test
    void updatesExistingFeedbackInsteadOfCreatingDuplicate() {
        persistReturnsArgument();
        AskFeedback existing = new AskFeedback(
            UUID.randomUUID(),
            ASK_LOG_ID,
            TENANT_ID,
            USER_ID,
            AskFeedbackRating.HELPFUL,
            null
        );
        when(askLogRepository.findByRequestIdAndTenantIdAndUserId(REQUEST_ID, TENANT_ID, USER_ID))
            .thenReturn(Optional.of(askLog()));
        when(askFeedbackRepository.findByAskLogId(ASK_LOG_ID)).thenReturn(Optional.of(existing));

        AskFeedbackResponse response = service.submit(
            USER,
            REQUEST_ID,
            new AskFeedbackRequest(AskFeedbackRating.NOT_HELPFUL, "引用不准确")
        );

        assertThat(response.rating()).isEqualTo(AskFeedbackRating.NOT_HELPFUL);
        assertThat(response.reason()).isEqualTo("引用不准确");
        ArgumentCaptor<AskFeedback> saved = ArgumentCaptor.forClass(AskFeedback.class);
        verify(askFeedbackRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
    }

    @Test
    void convertsBlankReasonToNull() {
        persistReturnsArgument();
        when(askLogRepository.findByRequestIdAndTenantIdAndUserId(REQUEST_ID, TENANT_ID, USER_ID))
            .thenReturn(Optional.of(askLog()));
        when(askFeedbackRepository.findByAskLogId(ASK_LOG_ID)).thenReturn(Optional.empty());

        AskFeedbackResponse response = service.submit(
            USER,
            REQUEST_ID,
            new AskFeedbackRequest(AskFeedbackRating.HELPFUL, " \n\t ")
        );

        assertThat(response.reason()).isNull();
    }

    @Test
    void hidesMissingOrCrossTenantAskBehindNotFound() {
        when(askLogRepository.findByRequestIdAndTenantIdAndUserId(REQUEST_ID, TENANT_ID, USER_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
            USER,
            REQUEST_ID,
            new AskFeedbackRequest(AskFeedbackRating.HELPFUL, null)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);
    }

    private AskLog askLog() {
        return new AskLog(
            ASK_LOG_ID,
            TENANT_ID,
            REQUEST_ID,
            USER_ID,
            "测试问题",
            true,
            10,
            AskResultStatus.ANSWERED,
            null,
            100,
            10,
            20,
            70,
            "test-model",
            "openai-compatible",
            5,
            0.35
        );
    }

    private void persistReturnsArgument() {
        when(askFeedbackRepository.save(any(AskFeedback.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
