package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptGuardTests {
    private static final String BOUNDARY = "0123456789abcdef";

    private final PromptGuard guard = new PromptGuard();

    @Test
    void generatesDistinctBoundariesPerRequest() {
        assertThat(guard.newBoundary()).matches("[0-9a-f]{16}").isNotEqualTo(guard.newBoundary());
    }

    @Test
    void acceptsPlainAnswerWithoutLinks() {
        assertThat(unsafe("保修期为一年。", hit("保修期为一年。"), "保修期多久？")).isFalse();
    }

    @Test
    void acceptsLinkThatIsPresentInTheRetrievedChunkIgnoringCaseAndTrailingPunctuation() {
        List<ChunkSearchResult> hits = List.of(hit("政策见 https://Support.example.com/Warranty 。"));

        assertThat(unsafe("详见 https://support.example.com/warranty。", hits, "政策在哪？")).isFalse();
        assertThat(unsafe("详见（https://support.example.com/warranty）", hits, "政策在哪？")).isFalse();
    }

    @Test
    void acceptsLinkTheUserAskedAbout() {
        assertThat(unsafe("https://example.com/a 是官网。", hit("官网是示例站点。"), "https://example.com/a 是什么？"))
            .isFalse();
    }

    @Test
    void rejectsLinkThatIsNotInTheContext() {
        assertThat(unsafe("见 https://evil.example/c?d=1", hit("保修期为一年。"), "保修期多久？")).isTrue();
    }

    @Test
    void rejectsLinkThatOnlyExtendsAContextLinkWithAQueryString() {
        List<ChunkSearchResult> hits = List.of(hit("见 https://example.com/help"));

        assertThat(unsafe("见 https://example.com/help?token=abc", hits, "帮助在哪？")).isTrue();
    }

    @Test
    void rejectsMarkdownImageUnlessTheSourceAlreadyContainsOne() {
        assertThat(unsafe("![a](https://e.example/p.png)", hit("保修期为一年。"), "保修期多久？")).isTrue();
        assertThat(unsafe("![a](p.png)", hit("图示：![a](p.png)"), "图示是什么？")).isFalse();
    }

    @Test
    void rejectsAnswerThatEchoesTheBoundary() {
        assertThat(unsafe("标记是 " + BOUNDARY, hit("保修期为一年。"), "保修期多久？")).isTrue();
    }

    @Test
    void ignoresBlankAnswers() {
        assertThat(unsafe("  ", hit("x"), "q")).isFalse();
        assertThat(unsafe(null, hit("x"), "q")).isFalse();
    }

    private boolean unsafe(String answer, ChunkSearchResult hit, String question) {
        return unsafe(answer, List.of(hit), question);
    }

    private boolean unsafe(String answer, List<ChunkSearchResult> hits, String question) {
        return guard.isUnsafeOutput(answer, BOUNDARY, hits, question);
    }

    private ChunkSearchResult hit(String content) {
        return new ChunkSearchResult(UUID.randomUUID(), UUID.randomUUID(), "faq.md", "chunk#1", content, 0.9);
    }
}
