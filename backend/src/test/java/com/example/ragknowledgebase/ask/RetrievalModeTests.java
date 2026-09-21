package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.common.BusinessException;
import org.junit.jupiter.api.Test;

class RetrievalModeTests {
    @Test
    void missingHeaderUsesVectorMode() {
        assertThat(RetrievalMode.fromHeader(null)).isEqualTo(RetrievalMode.VECTOR);
        assertThat(RetrievalMode.fromHeader("  ")).isEqualTo(RetrievalMode.VECTOR);
    }

    @Test
    void acceptsTheExplicitKeywordRrfExperimentMode() {
        assertThat(RetrievalMode.fromHeader("keyword-rrf")).isEqualTo(RetrievalMode.KEYWORD_RRF);
        assertThat(RetrievalMode.fromHeader(" VECTOR ")).isEqualTo(RetrievalMode.VECTOR);
    }

    @Test
    void rejectsUnknownModesAsAClientError() {
        assertThatThrownBy(() -> RetrievalMode.fromHeader("bm25"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(400);
    }
}
