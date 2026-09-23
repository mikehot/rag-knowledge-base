package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdjacentChunkRetrieverTests {
    private static final UUID FAQ_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final AdjacentChunkRetriever retriever = new AdjacentChunkRetriever();

    @Test
    void replacesTheLowestRankedDifferentDocumentWithAnAdjacentCandidateWithinTwoRanks() {
        List<ChunkSearchResult> candidates = List.of(
            hit("faq-2", FAQ_ID, "faq.md", "chunk#2", 0.92),
            hit("manual", uuid(2), "manual.md", "chunk#1", 0.91),
            hit("sla", uuid(3), "sla.md", "chunk#1", 0.90),
            hit("release", uuid(4), "release.md", "chunk#1", 0.89),
            hit("noise", uuid(5), "noise.md", "chunk#1", 0.88),
            hit("faq-1", FAQ_ID, "faq.md", "chunk#1", 0.87),
            hit("faq-3", FAQ_ID, "faq.md", "chunk#3", 0.86)
        );

        List<ChunkSearchResult> selected = retriever.select(candidates, 5);

        assertThat(selected).containsExactly(
            candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3), candidates.get(5)
        );
        assertThat(selected).hasSize(5).doesNotContain(candidates.get(4));
    }

    @Test
    void doesNotExpandAcrossDocumentsOrBeyondTheConfiguredRankWindow() {
        UUID sameFilenameDifferentDocument = uuid(6);
        List<ChunkSearchResult> candidates = List.of(
            hit("faq-2", FAQ_ID, "faq.md", "chunk#2", 0.92),
            hit("manual", uuid(2), "manual.md", "chunk#1", 0.91),
            hit("sla", uuid(3), "sla.md", "chunk#1", 0.90),
            hit("release", uuid(4), "release.md", "chunk#1", 0.89),
            hit("noise", uuid(5), "noise.md", "chunk#1", 0.88),
            hit("other-doc-same-name", sameFilenameDifferentDocument, "faq.md", "chunk#1", 0.87),
            hit("faq-non-neighbor", FAQ_ID, "faq.md", "chunk#4", 0.86),
            hit("faq-1-too-far", FAQ_ID, "faq.md", "chunk#1", 0.85)
        );

        assertThat(retriever.select(candidates, 5)).containsExactlyElementsOf(candidates.subList(0, 5));
    }

    @Test
    void returnsAvailableCandidatesAndRejectsInvalidBudget() {
        List<ChunkSearchResult> candidates = List.of(hit("only", FAQ_ID, "faq.md", "chunk#1", 0.9));

        assertThat(retriever.select(candidates, 5)).containsExactlyElementsOf(candidates);
        assertThatThrownBy(() -> retriever.select(candidates, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("contextK must be positive");
    }

    private static ChunkSearchResult hit(
        String id,
        UUID documentId,
        String filename,
        String locator,
        double similarity
    ) {
        return new ChunkSearchResult(UUID.nameUUIDFromBytes(id.getBytes()), documentId, filename, locator, id, similarity);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("20000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
