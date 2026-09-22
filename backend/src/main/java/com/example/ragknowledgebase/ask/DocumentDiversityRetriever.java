package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluation-only selector that prevents one document from occupying the
 * entire context before the remaining slots are filled by vector order.
 */
public class DocumentDiversityRetriever {
    public List<ChunkSearchResult> select(List<ChunkSearchResult> candidates, int contextK) {
        if (contextK < 1) {
            throw new IllegalArgumentException("contextK must be positive");
        }
        List<ChunkSearchResult> selected = new ArrayList<>();
        Set<UUID> selectedDocuments = new HashSet<>();
        Set<UUID> selectedChunks = new HashSet<>();
        for (ChunkSearchResult candidate : candidates) {
            if (selected.size() == contextK) {
                return selected;
            }
            if (!selectedDocuments.add(candidate.documentId())) {
                continue;
            }
            selected.add(candidate);
            selectedChunks.add(candidate.chunkId());
        }
        for (ChunkSearchResult candidate : candidates) {
            if (selected.size() == contextK) {
                break;
            }
            if (selectedChunks.add(candidate.chunkId())) {
                selected.add(candidate);
            }
        }
        return selected;
    }
}
