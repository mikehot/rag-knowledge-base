package com.example.ragknowledgebase.ask;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AskFeedbackRequest(
    @NotNull(message = "请选择反馈结果")
    AskFeedbackRating rating,
    @Size(max = 500, message = "反馈原因不能超过 500 字")
    String reason
) {
}
