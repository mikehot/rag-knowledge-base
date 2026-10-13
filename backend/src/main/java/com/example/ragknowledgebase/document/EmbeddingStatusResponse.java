package com.example.ragknowledgebase.document;

import java.util.List;

/**
 * Which embedding model produced the stored chunk vectors, and how far a model switch has been
 * rebuilt. Counts are for the caller's tenant and exclude deleted documents.
 */
public record EmbeddingStatusResponse(
    String configuredModel,
    List<ModelUsage> models,
    long indexedDocuments,
    long documentsOnConfiguredModel,
    long documentsWaitingForRebuild,
    boolean rebuildComplete
) {
    public record ModelUsage(
        String model,
        int dimensions,
        long chunks,
        long documents,
        boolean configured
    ) {
    }
}
