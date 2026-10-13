package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt-injection controls that do not depend on model behavior.
 *
 * <p>Retrieved chunks and the user question are untrusted data. The prompt marks them with a
 * boundary that document text cannot predict, and the answer is checked after
 * generation: it must not echo the boundary and must not carry links or images that appear
 * nowhere in the retrieved context, which is the usual way an injected instruction exfiltrates
 * data. Citations and ACL filtering are enforced elsewhere and are not affected by this class.
 */
final class PromptGuard {
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"'<]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}>\"'，。；：！？）】》”’、";
    private static final String MARKDOWN_IMAGE = "![";

    private final byte[] key;

    /** A guard whose boundaries are keyed by a process-local random secret. */
    PromptGuard() {
        this(randomKey());
    }

    /**
     * @param key server-side secret. Boundaries are an HMAC of the request under this key, so a document
     *            author cannot predict them, yet identical requests get identical prompts.
     */
    PromptGuard(byte[] key) {
        this.key = key.clone();
    }

    /** Derives the key from a server secret that is already configured (for example the JWT secret). */
    static PromptGuard keyedBy(String secret) {
        if (secret == null || secret.isBlank()) {
            return new PromptGuard();
        }
        return new PromptGuard(hmac("rag-prompt-boundary-v1".getBytes(StandardCharsets.UTF_8), secret));
    }

    /**
     * The data boundary for one request. It is deterministic in (question, retrieved chunk ids): a random
     * boundary changed the prompt text on every call and, at temperature 0, flipped borderline answers
     * between runs of the very same question.
     */
    String boundaryFor(String question, List<ChunkSearchResult> hits) {
        StringBuilder material = new StringBuilder(question == null ? "" : question);
        for (ChunkSearchResult hit : hits) {
            material.append('\n').append(hit.chunkId());
        }
        byte[] digest = hmac(key, material.toString());
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
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
