package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt-injection controls that do not depend on model behavior.
 *
 * <p>Retrieved chunks and the user question are untrusted data. The prompt marks them with a
 * per-request random boundary that document text cannot predict, and the answer is checked after
 * generation: it must not echo the boundary and must not carry links or images that appear
 * nowhere in the retrieved context, which is the usual way an injected instruction exfiltrates
 * data. Citations and ACL filtering are enforced elsewhere and are not affected by this class.
 */
final class PromptGuard {
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"'<]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}>\"'，。；：！？）】》”’、";
    private static final String MARKDOWN_IMAGE = "![";

    private final SecureRandom random = new SecureRandom();

    String newBoundary() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    String dataOpen(String boundary, String label) {
        return "<<" + label + "-" + boundary + ">>";
    }

    String dataClose(String boundary, String label) {
        return "<<END-" + label + "-" + boundary + ">>";
    }

    /**
     * Returns true when the answer should be handed off instead of shown.
     */
    boolean isUnsafeOutput(String answer, String boundary, List<ChunkSearchResult> hits, String question) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (answer.contains(boundary)) {
            return true;
        }
        String allowedText = allowedText(hits, question);
        if (answer.contains(MARKDOWN_IMAGE) && !allowedText.contains(MARKDOWN_IMAGE)) {
            return true;
        }
        Matcher matcher = URL.matcher(answer);
        while (matcher.find()) {
            String url = stripTrailingPunctuation(matcher.group()).toLowerCase(Locale.ROOT);
            if (!allowedText.contains(url)) {
                return true;
            }
        }
        return false;
    }

    private String allowedText(List<ChunkSearchResult> hits, String question) {
        StringBuilder builder = new StringBuilder(question == null ? "" : question);
        for (ChunkSearchResult hit : hits) {
            builder.append('\n').append(hit.content());
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
