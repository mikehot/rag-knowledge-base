package com.example.ragknowledgebase.ask;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic check that the chunks a model cited actually contain what its answer says.
 *
 * <p>The backend already guarantees that a cited index exists; it did not check that the cited chunk
 * supports the answer, so a correct answer could be shown next to the wrong evidence. Support is the
 * share of the answer's content units (digits, identifiers, Chinese character bigrams, with Chinese
 * numerals normalised) found in the chunk text. It is lexical on purpose: cheap, explainable, and
 * testable. It cannot judge whether an answer is complete or true, only whether its words come from
 * the cited evidence, so it repairs citations and flags answers no retrieved chunk supports.
 *
 * <p>Thresholds were calibrated offline on 278 correct answers captured from corpus-v1 runs: cited
 * support had median 0.95 and 10th percentile 0.80, and the union of all retrieved chunks never fell
 * below 0.50.
 */
final class CitationSupport {
    enum Outcome {
        /** The cited chunks already support the answer, or there is nothing to compare. */
        SUPPORTED,
        /** A better supporting set exists among the retrieved chunks; use {@link Result#indexes()}. */
        REPAIRED,
        /** No retrieved chunk supports the answer. */
        UNSUPPORTED,
        /** Weakly supported, but no better evidence was retrieved; keep the model's citations. */
        UNCHANGED
    }

    record Result(Outcome outcome, List<Integer> indexes, double citedSupport, double bestSupport) {
    }

    static final double KEEP_AT_LEAST = 0.6;
    static final double UNSUPPORTED_BELOW = 0.3;
    static final double MIN_GAIN = 0.2;
    static final double COVER_TARGET = 0.8;
    static final int MAX_REPAIRED_SOURCES = 3;

    private static final String STOP_CHARACTERS = "的了是在和与及或也都就而且以为有被把对不可能会应该需要进行之其该这那些个种中上下时等各每所可";
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:\\d+|[一二三四五六七八九十]+)[.、)）]\\s*");
    private static final Pattern CHINESE_NUMERAL = Pattern.compile("[零〇一二两三四五六七八九十百]+");
    private static final Pattern LATIN_OR_NUMBER = Pattern.compile("[a-z][a-z0-9_.\\-]+|\\d+(?:\\.\\d+)?%?");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /**
     * @param chunks       retrieved chunk texts in rank order; index i is citation number i + 1
     * @param citedIndexes the model's 1-based citations, already validated against {@code chunks}
     */
    Result evaluate(String answer, List<String> chunks, List<Integer> citedIndexes) {
        Set<String> target = units(answer);
        if (target.isEmpty() || chunks.isEmpty()) {
            return new Result(Outcome.SUPPORTED, citedIndexes, 1.0, 1.0);
        }
        List<Set<String>> chunkUnits = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            chunkUnits.add(units(chunk));
        }
        Set<String> citedUnits = new HashSet<>();
        for (int index : citedIndexes) {
            citedUnits.addAll(chunkUnits.get(index - 1));
        }
        double cited = coverage(target, citedUnits);
        if (cited >= KEEP_AT_LEAST) {
            return new Result(Outcome.SUPPORTED, citedIndexes, cited, cited);
        }

        Set<String> covered = new HashSet<>();
        TreeSet<Integer> chosen = new TreeSet<>();
        while (chosen.size() < MAX_REPAIRED_SOURCES && coverage(target, covered) < COVER_TARGET) {
            int bestIndex = -1;
            int bestGain = 0;
            for (int i = 0; i < chunkUnits.size(); i++) {
                if (chosen.contains(i + 1)) {
                    continue;
                }
                int gain = 0;
                for (String unit : target) {
                    if (!covered.contains(unit) && chunkUnits.get(i).contains(unit)) {
                        gain++;
                    }
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            chosen.add(bestIndex + 1);
            covered.addAll(chunkUnits.get(bestIndex));
        }
        double best = coverage(target, covered);

        Set<String> everything = new HashSet<>();
        for (Set<String> units : chunkUnits) {
            everything.addAll(units);
        }
        if (coverage(target, everything) < UNSUPPORTED_BELOW) {
            return new Result(Outcome.UNSUPPORTED, citedIndexes, cited, best);
        }
        if (best >= KEEP_AT_LEAST && best - cited >= MIN_GAIN) {
            return new Result(Outcome.REPAIRED, List.copyOf(chosen), cited, best);
        }
        return new Result(Outcome.UNCHANGED, citedIndexes, cited, best);
    }

    private static double coverage(Set<String> target, Set<String> available) {
        if (target.isEmpty()) {
            return 1.0;
        }
        int hit = 0;
        for (String unit : target) {
            if (available.contains(unit)) {
                hit++;
            }
        }
        return (double) hit / target.size();
    }

    static Set<String> units(String text) {
        Set<String> units = new HashSet<>();
        if (text == null || text.isBlank()) {
            return units;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = LIST_MARKER.matcher(normalized).replaceAll("");
        normalized = replaceChineseNumerals(normalized);
        Matcher latin = LATIN_OR_NUMBER.matcher(normalized);
        while (latin.find()) {
            units.add(latin.group());
        }
        Matcher run = CJK_RUN.matcher(normalized);
        while (run.find()) {
            String characters = run.group();
            for (int i = 0; i + 1 < characters.length(); i++) {
                char first = characters.charAt(i);
                char second = characters.charAt(i + 1);
                if (STOP_CHARACTERS.indexOf(first) < 0 && STOP_CHARACTERS.indexOf(second) < 0) {
                    units.add(characters.substring(i, i + 2));
                }
            }
        }
        return units;
    }

    private static String replaceChineseNumerals(String text) {
        Matcher matcher = CHINESE_NUMERAL.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer value = chineseNumber(matcher.group());
            matcher.appendReplacement(
                result,
                Matcher.quoteReplacement(value == null ? matcher.group() : Integer.toString(value))
            );
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Integer chineseNumber(String numeral) {
        int total = 0;
        int current = 0;
        for (char character : numeral.toCharArray()) {
            switch (character) {
                case '零', '〇' -> current = 0;
                case '一' -> current = 1;
                case '二', '两' -> current = 2;
                case '三' -> current = 3;
                case '四' -> current = 4;
                case '五' -> current = 5;
                case '六' -> current = 6;
                case '七' -> current = 7;
                case '八' -> current = 8;
                case '九' -> current = 9;
                case '十' -> {
                    total += (current == 0 ? 1 : current) * 10;
                    current = 0;
                }
                case '百' -> {
                    total += (current == 0 ? 1 : current) * 100;
                    current = 0;
                }
                default -> {
                    return null;
                }
            }
        }
        return total + current;
    }
}
