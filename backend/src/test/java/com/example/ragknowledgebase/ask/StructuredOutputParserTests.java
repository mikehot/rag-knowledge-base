package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StructuredOutputParserTests {
    private final StructuredOutputParser parser = new StructuredOutputParser();

    @Test
    void parsesContractAndToleratesJsonCodeFence() {
        StructuredAnswer answer = parser.parse("```json\n"
            + "{\"answer\":\"按流程提交\",\"found\":true,\"grounded\":true,\"sourceIndexes\":[1,2]}"
            + "\n```");

        assertThat(answer.answer()).isEqualTo("按流程提交");
        assertThat(answer.found()).isTrue();
        assertThat(answer.grounded()).isTrue();
        assertThat(answer.sourceIndexes()).containsExactly(1, 2);
    }

    @Test
    void rejectsMissingRequiredFieldsAndInconsistentRefusal() {
        assertThatThrownBy(() -> parser.parse("{\"answer\":\"答案\",\"found\":true}"))
            .isInstanceOf(StructuredOutputException.class)
            .hasMessageContaining("grounded");

        assertThatThrownBy(() -> parser.parse(
            "{\"answer\":\"未找到相关信息，建议转人工。\",\"found\":false,\"grounded\":true,\"sourceIndexes\":[]}"))
            .isInstanceOf(StructuredOutputException.class)
            .hasMessageContaining("grounded");
    }

    @Test
    void rejectsNonJsonOutput() {
        assertThatThrownBy(() -> parser.parse("plain text"))
            .isInstanceOf(StructuredOutputException.class);
    }
}
