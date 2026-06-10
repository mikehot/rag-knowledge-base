package com.example.ragknowledgebase.ask;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(
    @NotBlank(message = "请输入问题")
    String question
) {
}
