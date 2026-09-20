package com.example.ragknowledgebase.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public class StructuredOutputParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StructuredAnswer parse(String raw) {
        String candidate = extractJsonObject(raw);
        final JsonNode root;
        try {
            root = objectMapper.readTree(candidate);
        } catch (JsonProcessingException ex) {
            throw new StructuredOutputException("模型输出不是有效 JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new StructuredOutputException("模型输出必须是 JSON 对象");
        }

        JsonNode answerNode = root.get("answer");
        JsonNode foundNode = root.get("found");
        JsonNode groundedNode = root.get("grounded");
        JsonNode sourceIndexesNode = root.get("sourceIndexes");
        if (answerNode == null || !answerNode.isTextual() || answerNode.asText().isBlank()) {
            throw new StructuredOutputException("answer 必须是非空字符串");
        }
        if (foundNode == null || !foundNode.isBoolean()) {
            throw new StructuredOutputException("found 必须是布尔值");
        }
        if (groundedNode == null || !groundedNode.isBoolean()) {
            throw new StructuredOutputException("grounded 必须是布尔值");
        }
        if (sourceIndexesNode == null || !sourceIndexesNode.isArray()) {
            throw new StructuredOutputException("sourceIndexes 必须是整数数组");
        }

        List<Integer> sourceIndexes = new ArrayList<>();
        for (JsonNode indexNode : sourceIndexesNode) {
            if (!indexNode.isIntegralNumber() || indexNode.asInt() < 1) {
                throw new StructuredOutputException("sourceIndexes 只能包含正整数");
            }
            sourceIndexes.add(indexNode.asInt());
        }
        if (!foundNode.asBoolean() && !sourceIndexes.isEmpty()) {
            throw new StructuredOutputException("found=false 时 sourceIndexes 必须为空");
        }
        if (!foundNode.asBoolean() && groundedNode.asBoolean()) {
            throw new StructuredOutputException("found=false 时 grounded 必须为 false");
        }

        return new StructuredAnswer(
            answerNode.asText().trim(),
            foundNode.asBoolean(),
            groundedNode.asBoolean(),
            sourceIndexes
        );
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new StructuredOutputException("模型输出为空");
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLineBreak = value.indexOf('\n');
            int closingFence = value.lastIndexOf("```");
            if (firstLineBreak > 0 && closingFence > firstLineBreak) {
                value = value.substring(firstLineBreak + 1, closingFence).trim();
            }
        }
        int objectStart = value.indexOf('{');
        int objectEnd = value.lastIndexOf('}');
        if (objectStart < 0 || objectEnd < objectStart) {
            throw new StructuredOutputException("模型输出缺少 JSON 对象");
        }
        return value.substring(objectStart, objectEnd + 1);
    }
}
