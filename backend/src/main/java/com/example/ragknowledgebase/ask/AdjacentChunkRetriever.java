package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluation-only, ACL-safe adjacent chunk substitution over already-filtered vector hits. */
public class AdjacentChunkRetriever {
    private static final Pattern CHUNK_LOCATOR = Pattern.compile("chunk#(\\d+)");
    private static final int MAX_RANK_WINDOW = 2;

    public List<ChunkSearchResult> select(List<ChunkSearchResult> candidates, int contextK) {
        if (contextK < 1) {
            throw new IllegalArgumentException("contextK must be positive");
        }
        List<ChunkSearchResult> selected = new ArrayList<>(candidates.subList(0, Math.min(contextK, candidates.size())));
        if (selected.isEmpty() || candidates.size() <= selected.size()) {
            return selected;
        }

        int candidateLimit = Math.min(candidates.size(), contextK + MAX_RANK_WINDOW);
        for (ChunkSearchResult seed : List.copyOf(selected)) {
            Integer sequence = sequence(seed.locator());
            if (sequence == null) {
                continue;
            }
            for (int candidateIndex = 0; candidateIndex < candidateLimit; candidateIndex++) {
                ChunkSearchResult candidate = candidates.get(candidateIndex);
                if (candidateIndex < selected.size() || !candidate.documentId().equals(seed.documentId())) {
                    continue;
                }
                Integer candidateSequence = sequence(candidate.locator());
                if (candidateSequence == null || Math.abs(candidateSequence - sequence) != 1) {
                    continue;
                }
                int victimIndex = lowestRankedDifferentDocument(selected, candidate);
                if (victimIndex < 0) {
                    continue;
                }
                selected.set(victimIndex, candidate);
                selected.sort((left, right) -> Integer.compare(candidates.indexOf(left), candidates.indexOf(right)));
                return selected;
            }
        }
        return selected;
    }

    private static int lowestRankedDifferentDocument(
        List<ChunkSearchResult> selected,
        ChunkSearchResult neighbor
    ) {
        int victimIndex = -1;
        for (int index = 0; index < selected.size(); index++) {
            if (selected.get(index).documentId().equals(neighbor.documentId())) {
                continue;
            }
            if (victimIndex < 0 || index > victimIndex) {
                victimIndex = index;
            }
        }
        return victimIndex;
    }

    private static Integer sequence(String locator) {
        Matcher matcher = CHUNK_LOCATOR.matcher(locator == null ? "" : locator);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
