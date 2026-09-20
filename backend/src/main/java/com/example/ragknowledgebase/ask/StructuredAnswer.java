package com.example.ragknowledgebase.ask;

import java.util.List;

/**
 * The only model-owned part of the answer contract.
 *
 * Source metadata is deliberately kept out of this record. The backend maps
 * sourceIndexes to the ACL-filtered retrieval results instead of trusting the
 * model to invent document identifiers or citations.
 */
public record StructuredAnswer(
    String answer,
    boolean found,
    boolean grounded,
    List<Integer> sourceIndexes
) {
    public StructuredAnswer {
        sourceIndexes = List.copyOf(sourceIndexes);
    }
}
