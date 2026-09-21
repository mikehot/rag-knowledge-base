package com.example.ragknowledgebase.ask;

import java.util.regex.Pattern;

/**
 * Conservative, deterministic classifier for questions that explicitly request multiple answer parts.
 * It is deliberately not an LLM call so the routing decision is cheap, explainable, and testable.
 */
public final class QuestionComplexityClassifier {
    private static final Pattern EXPLICIT_MULTI_PART = Pattern.compile(
        "同时|分别|综合|以及|一并|各自|并说明|并回答"
    );
    private static final Pattern MULTIPLE_INTERROGATIVES = Pattern.compile(
        "(?:多久|多少|谁|什么|哪个|哪种|如何).{0,30}(?:多久|多少|谁|什么|哪个|哪种|如何)"
    );

    public boolean isComplex(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim();
        long questionMarks = normalized.chars()
            .filter(character -> character == '?' || character == '？')
            .count();
        return EXPLICIT_MULTI_PART.matcher(normalized).find()
            || MULTIPLE_INTERROGATIVES.matcher(normalized).find()
            || questionMarks >= 2
            || normalized.contains("；");
    }
}
