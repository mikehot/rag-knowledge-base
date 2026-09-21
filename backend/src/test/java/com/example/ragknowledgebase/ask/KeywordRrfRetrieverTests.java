package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeywordRrfRetrieverTests {
    private static final UUID VECTOR_CHUNK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID KEYWORD_CHUNK_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID FORBIDDEN_CHUNK_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

    private final KeywordRrfRetriever retriever = new KeywordRrfRetriever();

    @Test
    void keywordWeightCanMoveExactPhraseAboveVectorDistractor() {
        ChunkSearchResult vectorDistractor = chunk(
            VECTOR_CHUNK_ID,
            "operations.md",
            "设备需要重新启动后再检查网络。",
            0.92
        );
        ChunkSearchResult exactKeyword = chunk(
            KEYWORD_CHUNK_ID,
            "sample_faq.md",
            "保修期限为一年，申请时需要提供订单信息。",
            0.0
        );

        List<ChunkSearchResult> hits = retriever.retrieve(
            "保修期限是多少？",
            List.of(vectorDistractor),
            List.of(vectorDistractor, exactKeyword),
            1,
            10,
            2.0,
            60
        );

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo(KEYWORD_CHUNK_ID);
            assertThat(hit.filename()).isEqualTo("sample_faq.md");
            assertThat(hit.similarity()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void onlyCallerVisibleChunksCanEnterTheFusedContext() {
        ChunkSearchResult vectorHit = chunk(
            VECTOR_CHUNK_ID,
            "sample_faq.md",
            "保修期限为一年。",
            0.91
        );
        ChunkSearchResult forbidden = chunk(
            FORBIDDEN_CHUNK_ID,
            "salary-policy.md",
            "薪资信息不得对外公开。",
            0.0
        );

        List<ChunkSearchResult> hits = retriever.retrieve(
            "薪资信息是什么？",
            List.of(vectorHit),
            List.of(vectorHit),
            5,
            10,
            2.0,
            60
        );

        assertThat(hits).extracting(ChunkSearchResult::filename).containsExactly("sample_faq.md");
        assertThat(hits).extracting(ChunkSearchResult::chunkId).doesNotContain(forbidden.chunkId());
    }

    private ChunkSearchResult chunk(UUID id, String filename, String content, double similarity) {
        return new ChunkSearchResult(id, UUID.randomUUID(), filename, "chunk#1", content, similarity);
    }
}
