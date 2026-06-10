package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.config.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VectorSchemaInitializer implements ApplicationRunner {
    private final AppProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public VectorSchemaInitializer(AppProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.rag().enableVectorSchema()) {
            return;
        }
        int dim = properties.rag().embeddingDim();
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chunk (
              id UUID PRIMARY KEY,
              document_id UUID REFERENCES document(id) ON DELETE CASCADE,
              seq INTEGER,
              locator TEXT,
              content TEXT NOT NULL,
              embedding vector(%d),
              created_at TIMESTAMPTZ DEFAULT now()
            )
            """.formatted(dim));
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunk_document ON chunk(document_id)");
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops)"
        );
    }
}
