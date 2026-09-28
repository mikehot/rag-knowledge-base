package com.example.ragknowledgebase.ask;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AskDailyUsageRepository {
    private final JdbcTemplate jdbcTemplate;

    public AskDailyUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically reserves one ask for the user on the given day. Returns false when the
     * limit is already reached; concurrent callers serialize on the same row.
     */
    public boolean tryReserve(UUID userId, LocalDate day, int limit) {
        return jdbcTemplate.update(
            """
                INSERT INTO ask_daily_usage (user_id, usage_date, ask_count)
                VALUES (?, ?, 1)
                ON CONFLICT (user_id, usage_date) DO UPDATE
                SET ask_count = ask_daily_usage.ask_count + 1
                WHERE ask_daily_usage.ask_count < ?
                """,
            userId,
            day,
            limit
        ) == 1;
    }
}
