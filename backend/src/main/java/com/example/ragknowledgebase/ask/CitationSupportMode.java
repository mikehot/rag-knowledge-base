package com.example.ragknowledgebase.ask;

import java.util.Locale;

/**
 * How the backend treats citations whose chunks do not support the answer text.
 *
 * <ul>
 *   <li>{@link #OFF}: citations are exactly what the model chose.
 *   <li>{@link #REPAIR}: when a better supporting chunk was retrieved, cite it instead.
 *   <li>{@link #ENFORCE}: as REPAIR, and hand the question off when no retrieved chunk supports the
 *       answer at all.
 * </ul>
 */
public enum CitationSupportMode {
    OFF,
    REPAIR,
    ENFORCE;

    public static CitationSupportMode from(String value) {
        if (value == null || value.isBlank()) {
            return REPAIR;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return REPAIR;
        }
    }
}
