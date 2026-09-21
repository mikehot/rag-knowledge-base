package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluation-only in-memory keyword scorer. The caller must provide ACL-filtered
 * chunks. This intentionally mirrors the versioned Python candidate benchmark;
 * it is not a replacement for a production full-text index.
 */
public class KeywordRrfRetriever {
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final List<String> STOP_PHRASES = List.of(
        "请同时说明", "请说明", "请给出", "请问", "告诉我", "有哪些", "哪个", "哪些", "什么",
        "如何", "怎么", "怎样", "为什么", "多少", "是否", "有没有", "可以", "能否", "分别",
        "说明", "给出", "需要", "要求", "情况", "相关", "时候", "以及", "后", "前", "的", "吗", "呢", "请"
    );

    public List<ChunkSearchResult> retrieve(
        String question,
        List<ChunkSearchResult> vectorHits,
        List<ChunkSearchResult> visibleChunks,
        int contextK,
        int candidateK,
        double keywordWeight,
        int rrfK
    ) {
        if (contextK < 1 || candidateK < contextK || keywordWeight <= 0 || rrfK < 1) {
            throw new IllegalArgumentException("invalid keyword retrieval parameters");
        }
        List<RankedChunk> keywordHits = rankKeyword(question, visibleChunks, candidateK);
        Map<UUID, ChunkSearchResult> chunksById = new HashMap<>();
        for (ChunkSearchResult chunk : visibleChunks) {
            chunksById.put(chunk.chunkId(), chunk);
        }
        for (ChunkSearchResult chunk : vectorHits) {
            chunksById.putIfAbsent(chunk.chunkId(), chunk);
        }

        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int index = 0; index < vectorHits.size(); index++) {
            ChunkSearchResult hit = vectorHits.get(index);
            scores.merge(hit.chunkId(), 1.0 / (rrfK + index + 1), Double::sum);
            ranks.put(hit.chunkId(), index + 1);
        }
        for (RankedChunk hit : keywordHits) {
            scores.merge(hit.chunk().chunkId(), keywordWeight / (rrfK + hit.rank()), Double::sum);
            ranks.putIfAbsent(hit.chunk().chunkId(), hit.rank());
        }

        List<UUID> ordered = new ArrayList<>(scores.keySet());
        ordered.sort(Comparator
            .comparing((UUID id) -> scores.get(id), Comparator.reverseOrder())
            .thenComparing(id -> chunksById.get(id).filename())
            .thenComparing(UUID::toString));
        List<ChunkSearchResult> result = new ArrayList<>();
        for (UUID chunkId : ordered.subList(0, Math.min(contextK, ordered.size()))) {
            ChunkSearchResult chunk = chunksById.get(chunkId);
            double score = Math.min(1.0, scores.get(chunkId));
            result.add(new ChunkSearchResult(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.filename(),
                chunk.locator(),
                chunk.content(),
                score
            ));
        }
        return result;
    }

    private List<RankedChunk> rankKeyword(String question, List<ChunkSearchResult> chunks, int limit) {
        Map<UUID, Map<String, Integer>> termCounts = new HashMap<>();
        Map<UUID, List<String>> phrases = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (ChunkSearchResult chunk : chunks) {
            List<String> tokens = tokenize(chunk.content(), true);
            Map<String, Integer> counts = counts(tokens);
            termCounts.put(chunk.chunkId(), counts);
            phrases.put(chunk.chunkId(), phraseTokens(chunk.content(), true));
            totalLength += tokens.size();
            for (String term : counts.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = chunks.isEmpty() ? 1.0 : Math.max(1.0, totalLength / chunks.size());
        List<String> queryTokens = tokenize(question, true);
        Map<String, Integer> queryCounts = counts(queryTokens);
        List<String> queryPhrases = phraseTokens(question, true);
        List<RankedChunk> ranked = new ArrayList<>();
        for (ChunkSearchResult chunk : chunks) {
            Map<String, Integer> counts = termCounts.get(chunk.chunkId());
            int documentLength = Math.max(1, counts.values().stream().mapToInt(Integer::intValue).sum());
            double score = 0;
            Set<String> matchedTerms = new HashSet<>();
            for (Map.Entry<String, Integer> query : queryCounts.entrySet()) {
                int termFrequency = counts.getOrDefault(query.getKey(), 0);
                if (termFrequency == 0) {
                    continue;
                }
                matchedTerms.add(query.getKey());
                int frequency = documentFrequency.getOrDefault(query.getKey(), 0);
                double idf = Math.log(1.0 + (chunks.size() - frequency + 0.5) / (frequency + 0.5));
                double denominator = termFrequency + BM25_K1 * (1 - BM25_B + BM25_B * documentLength / averageLength);
                score += idf * (termFrequency * (BM25_K1 + 1) / denominator) * Math.min(query.getValue(), 2);
            }
            String normalizedContent = normalizeText(chunk.content());
            for (String phrase : new HashSet<>(queryPhrases)) {
                if (normalizedContent.contains(phrase)) {
                    score += Math.min(2.0, 0.25 * phrase.length());
                }
            }
            if (score > 0) {
                ranked.add(new RankedChunk(chunk, score, matchedTerms.size(), 0));
            }
        }
        ranked.sort(Comparator
            .comparing(RankedChunk::score, Comparator.reverseOrder())
            .thenComparing(hit -> hit.chunk().filename())
            .thenComparing(hit -> hit.chunk().locator(), Comparator.nullsFirst(String::compareTo))
            .thenComparing(hit -> hit.chunk().chunkId().toString()));
        List<RankedChunk> limited = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
            RankedChunk hit = ranked.get(index);
            limited.add(new RankedChunk(hit.chunk(), hit.score(), hit.matchedTermCount(), index + 1));
        }
        return limited;
    }

    private List<String> tokenize(String value, boolean normalized) {
        String text = normalizeText(value);
        if (normalized) {
            for (String phrase : STOP_PHRASES) {
                text = text.replace(phrase, " ");
            }
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isAsciiTokenChar(codePoint)) {
                ascii.appendCodePoint(codePoint);
                flushHan(han, tokens);
            } else {
                flushAscii(ascii, tokens);
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    han.appendCodePoint(codePoint);
                } else {
                    flushHan(han, tokens);
                }
            }
        }
        flushAscii(ascii, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private List<String> phraseTokens(String value, boolean normalized) {
        String text = normalizeText(value);
        if (normalized) {
            for (String phrase : STOP_PHRASES) {
                text = text.replace(phrase, " ");
            }
        }
        List<String> phrases = new ArrayList<>();
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                han.appendCodePoint(codePoint);
            } else {
                flushPhrases(han, phrases);
            }
        }
        flushPhrases(han, phrases);
        return phrases;
    }

    private void flushAscii(StringBuilder value, List<String> tokens) {
        if (value.length() > 0) {
            tokens.add(value.toString());
            value.setLength(0);
        }
    }

    private void flushHan(StringBuilder value, List<String> tokens) {
        if (value.length() == 0) {
            return;
        }
        String run = value.toString();
        for (int size : List.of(2, 3)) {
            for (int index = 0; index + size <= run.length();) {
                int end = run.offsetByCodePoints(index, size);
                tokens.add(run.substring(index, end));
                index = run.offsetByCodePoints(index, 1);
            }
        }
        value.setLength(0);
    }

    private void flushPhrases(StringBuilder value, List<String> phrases) {
        if (value.length() >= 2) {
            phrases.add(value.toString());
        }
        value.setLength(0);
    }

    private boolean isAsciiTokenChar(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= '0' && codePoint <= '9')
            || codePoint == '.' || codePoint == '_' || codePoint == ':'
            || codePoint == '/' || codePoint == '%' || codePoint == '+' || codePoint == '-';
    }

    private Map<String, Integer> counts(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private String normalizeText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }

    private record RankedChunk(ChunkSearchResult chunk, double score, int matchedTermCount, int rank) {
    }
}
