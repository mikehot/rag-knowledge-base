package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDiversityRetrieverTests {
    private static final UUID DOCUMENT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_C = UUID.fromString("20000000-0000-0000-0000-000000000003");

    private final DocumentDiversityRetriever retriever = new DocumentDiversityRetriever();

    @Test
    void selectsOneChunkPerDocumentBeforeFillingRemainingSlots() {
        List<ChunkSearchResult> candidates = List.of(
            hit(DOCUMENT_A, "a-1", 0.95),
            hit(DOCUMENT_A, "a-2", 0.94),
            hit(DOCUMENT_B, "b-1", 0.93),
            hit(DOCUMENT_C, "c-1", 0.92)
        );

        List<ChunkSearchResult> selected = retriever.select(candidates, 3);

        assertThat(selected).extracting(ChunkSearchResult::locator)
            .containsExactly("a-1", "b-1", "c-1");
    }

    @Test
    void rejectsNonPositiveContextSize() {
        assertThatThrownBy(() -> retriever.select(List.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ChunkSearchResult hit(UUID documentId, String locator, double similarity) {
        return new ChunkSearchResult(UUID.randomUUID(), documentId, documentId.toString(), locator, "content", similarity);
    }
}
