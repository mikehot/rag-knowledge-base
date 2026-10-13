package com.example.ragknowledgebase.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Logs once at startup when stored chunks were produced by a different embedding model than the
 * configured one. Those documents are skipped by search until they are reindexed, which would
 * otherwise look like questions suddenly finding nothing.
 */
@Component
class EmbeddingModelStartupCheck implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingModelStartupCheck.class);

    private final JdbcTemplate jdbcTemplate;
    private final ChunkJdbcRepository chunkRepository;

    EmbeddingModelStartupCheck(JdbcTemplate jdbcTemplate, ChunkJdbcRepository chunkRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkRepository = chunkRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long foreign = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunk WHERE embedding_model <> ?",
                Long.class,
                chunkRepository.embeddingModel()
            );
            if (foreign != null && foreign > 0) {
                LOGGER.warn(
                    "{} stored chunks were embedded with a model other than '{}'; they are not searchable "
                        + "until their documents are reindexed (GET /api/admin/embeddings shows progress)",
                    foreign,
                    chunkRepository.embeddingModel()
                );
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("Embedding model startup check skipped: {}", ex.getClass().getSimpleName());
        }
    }
}
