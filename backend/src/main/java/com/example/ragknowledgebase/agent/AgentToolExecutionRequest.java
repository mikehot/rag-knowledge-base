package com.example.ragknowledgebase.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AgentToolExecutionRequest(
    @NotEmpty(message = "至少提供一个工具调用")
    @Size(max = 3, message = "单次最多调用 3 个只读工具")
    List<@Valid AgentToolCall> calls
) {
}
