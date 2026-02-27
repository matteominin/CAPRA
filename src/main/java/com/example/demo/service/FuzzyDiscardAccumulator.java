package com.example.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-request accumulator for issues discarded by fuzzy evidence anchoring.
 * Stored in an InheritableThreadLocal so child virtual threads share context.
 */
public final class FuzzyDiscardAccumulator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final InheritableThreadLocal<FuzzyDiscardAccumulator> CONTEXT =
            new InheritableThreadLocal<>() {
                @Override
                protected FuzzyDiscardAccumulator childValue(FuzzyDiscardAccumulator parent) {
                    return parent;
                }
            };

    private final List<DiscardedIssue> discarded = java.util.Collections.synchronizedList(new ArrayList<>());

    private FuzzyDiscardAccumulator() {}

    public static FuzzyDiscardAccumulator start() {
        FuzzyDiscardAccumulator acc = new FuzzyDiscardAccumulator();
        CONTEXT.set(acc);
        return acc;
    }

    public static FuzzyDiscardAccumulator current() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public void addDiscarded(String issueId, double similarity, String quoteSnippet, String quoteFull, String reason) {
        discarded.add(new DiscardedIssue(
                safe(issueId),
                similarity,
                safe(reason),
                safe(quoteSnippet),
                quoteFull == null ? null : safe(quoteFull)
        ));
    }

    public int count() {
        return discarded.size();
    }

    /**
     * Compact single-line representation for HTTP headers.
     * Hard-capped to avoid overlong headers.
     */
    public String asHeaderValue(int maxChars) {
        String joined = discarded.stream()
                .map(d -> "id=%s;sim=%.3f;reason=%s;quote=%s".formatted(
                        headerSafe(d.issueId()),
                        d.similarity(),
                        headerSafe(d.reason()),
                        headerSafe(d.quoteSnippet())))
                .reduce((a, b) -> a + " || " + b)
                .orElse("");
        joined = headerSafe(joined);
        if (joined.length() <= maxChars) return joined;
        return joined.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    /**
     * Structured export, encoded for safe transport in HTTP headers.
     * The payload includes a bounded sample to keep header size manageable.
     */
    public String asJsonBase64(int maxIssues) {
        int effectiveMax = Math.max(0, maxIssues);
        List<DiscardedIssue> snapshot;
        synchronized (discarded) {
            int end = Math.min(effectiveMax, discarded.size());
            snapshot = new ArrayList<>(discarded.subList(0, end));
        }
        Map<String, Object> envelope = Map.of(
                "totalDiscarded", discarded.size(),
                "returnedDiscarded", snapshot.size(),
                "truncated", discarded.size() > snapshot.size(),
                "issues", snapshot
        );
        try {
            String json = OBJECT_MAPPER.writeValueAsString(envelope);
            return java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n]+", " ").replace(";", ",").trim();
    }

    /**
     * HTTP header-safe ASCII representation (printable US-ASCII only).
     */
    private String headerSafe(String s) {
        if (s == null || s.isBlank()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c <= 126) {
                out.append(c);
            } else {
                out.append(' ');
            }
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    public record DiscardedIssue(
            String issueId,
            double similarity,
            String reason,
            String quoteSnippet,
            String quoteFull
    ) {}
}
