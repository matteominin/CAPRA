package com.example.demo.service;

import com.example.demo.model.AuditIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidence Anchoring service: NON-LLM verification that quotes
 * actually exist in the document using fuzzy string matching (normalized Levenshtein).
 * <p>
 * This is the single most effective improvement against hallucinations:
 * if the LLM fabricates a quote, the fuzzy match detects and discards it.
 * <p>
 * Approach:
 * - Normalize text and quotes (lowercase, remove extra spaces, punctuation)
 * - Search for the best match with sliding window in the document
 * - If the similarity score is below the threshold, the issue is discarded
 * - If the match is good, the confidence score is adjusted based on match quality
 */
@Service
public class EvidenceAnchoringService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAnchoringService.class);

    /** Minimum similarity threshold for accepting a quote (0.0-1.0). */
    private static final double MIN_SIMILARITY = 0.45;

    /** Threshold above which confidence gets boosted. */
    private static final double BOOST_THRESHOLD = 0.70;

    /**
     * Filters issues by verifying that quotes exist in the document.
     * Adjusts the confidence score based on match quality.
     *
     * @param documentText full text of the document
     * @param issues       issues to verify
     * @return filtered issues with updated confidence
     */
    public List<AuditIssue> anchorEvidence(String documentText, List<AuditIssue> issues) {
        if (issues.isEmpty()) return issues;

        String normalizedDoc = normalize(documentText);
        List<AuditIssue> anchored = new ArrayList<>();
        int rejected = 0;

        for (AuditIssue issue : issues) {
            if (issue.quote() == null || issue.quote().isBlank()) {
                // No quote: penalize confidence but don't discard
                anchored.add(issue.withConfidence(issue.confidenceScore() * 0.5));
                continue;
            }

            String normalizedQuote = normalize(issue.quote());
            if (normalizedQuote.length() < 15) {
                // Quote too short for reliable match: accept with penalty
                anchored.add(issue.withConfidence(issue.confidenceScore() * 0.7));
                continue;
            }

            double similarity = bestSlidingWindowSimilarity(normalizedDoc, normalizedQuote);

            if (similarity < MIN_SIMILARITY) {
                log.debug("EvidenceAnchoring: SCARTATA [{}] — quote non trovata (similarity={:.2f}): \"{}\"",
                        issue.id(), similarity, truncate(issue.quote(), 80));
                rejected++;
            } else {
                    // Adjust confidence based on match
                double adjustedConfidence;
                if (similarity >= BOOST_THRESHOLD) {
                    // Excellent match: boost up to +15%
                    adjustedConfidence = Math.min(1.0, issue.confidenceScore() * (1.0 + (similarity - BOOST_THRESHOLD) * 0.5));
                } else {
                    // Acceptable but not perfect match: proportional penalty
                    double penalty = 1.0 - ((BOOST_THRESHOLD - similarity) / (BOOST_THRESHOLD - MIN_SIMILARITY)) * 0.3;
                    adjustedConfidence = issue.confidenceScore() * penalty;
                }

                log.debug("EvidenceAnchoring: OK [{}] similarity={:.2f}, confidence {:.2f} → {:.2f}",
                        issue.id(), similarity, issue.confidenceScore(), adjustedConfidence);
                anchored.add(issue.withConfidence(adjustedConfidence));
            }
        }

        log.info("EvidenceAnchoring: {}/{} issues confirmed, {} discarded for quote not found",
                anchored.size(), issues.size(), rejected);

        return anchored;
    }

    /**
     * Finds the best match of `query` inside `text` using a sliding window
     * with normalized Levenshtein distance.
     * For performance, uses trigram overlap as a pre-filter.
     *
     * @return best similarity (0.0-1.0)
     */
    private double bestSlidingWindowSimilarity(String text, String query) {
        int queryLen = query.length();
        if (queryLen == 0) return 0.0;

        // Step 1: Try exact match (substring contains) — O(n)
        if (text.contains(query)) return 1.0;

        // Step 2: Fast trigram overlap on windows for pre-filtering
        // Use a window slightly larger than the query
        int windowSize = Math.min(text.length(), (int) (queryLen * 1.5));
        int step = Math.max(1, queryLen / 4);

        double bestSimilarity = 0.0;

        for (int i = 0; i <= text.length() - Math.min(queryLen / 2, windowSize); i += step) {
            int end = Math.min(i + windowSize, text.length());
            String window = text.substring(i, end);

            // Quick trigram overlap check
            double trigramScore = trigramOverlap(window, query);
            if (trigramScore < MIN_SIMILARITY * 0.6) continue; // skip dissimilar windows

            // Compute for windows with good trigram score
            double sim = normalizedLevenshteinSimilarity(window, query);
            bestSimilarity = Math.max(bestSimilarity, sim);

            if (bestSimilarity >= 0.95) break; // good enough
        }

        // Step 3: If large window didn't find well, try with window = queryLen
        if (bestSimilarity < BOOST_THRESHOLD && text.length() > queryLen) {
            step = Math.max(1, queryLen / 3);
            for (int i = 0; i <= text.length() - queryLen; i += step) {
                int end = Math.min(i + queryLen + queryLen / 4, text.length());
                String window = text.substring(i, end);

                double sim = normalizedLevenshteinSimilarity(window, query);
                bestSimilarity = Math.max(bestSimilarity, sim);

                if (bestSimilarity >= 0.95) break;
            }
        }

        return bestSimilarity;
    }

    /**
     * Computes normalized similarity between two strings using Levenshtein.
     * Returns 1.0 for identical strings, 0.0 for completely different.
     * For long strings, uses an approximate approach for performance.
     */
    private double normalizedLevenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;

        // For very long strings (>500 char), use block comparison
        if (a.length() > 500 || b.length() > 500) {
            return longestCommonSubsequenceRatio(a, b);
        }

        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Efficient Levenshtein distance computation (space O(min(m,n))).
     */
    private int levenshteinDistance(String a, String b) {
        if (a.length() > b.length()) { String t = a; a = b; b = t; }

        int[] prev = new int[a.length() + 1];
        int[] curr = new int[a.length() + 1];

        for (int i = 0; i <= a.length(); i++) prev[i] = i;

        for (int j = 1; j <= b.length(); j++) {
            curr[0] = j;
            for (int i = 1; i <= a.length(); i++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[i] = Math.min(Math.min(curr[i - 1] + 1, prev[i] + 1), prev[i - 1] + cost);
            }
            int[] temp = prev; prev = curr; curr = temp;
        }

        return prev[a.length()];
    }

    /**
     * LCS ratio for long strings — O(n*m) with early exit.
     */
    private double longestCommonSubsequenceRatio(String a, String b) {
        // Use word-level for performance
        String[] wordsA = a.split("\\s+");
        String[] wordsB = b.split("\\s+");

        int m = wordsA.length, n = wordsB.length;
        if (m == 0 || n == 0) return 0.0;

        // Limit for performance
        if (m > 200) { wordsA = java.util.Arrays.copyOf(wordsA, 200); m = 200; }
        if (n > 200) { wordsB = java.util.Arrays.copyOf(wordsB, 200); n = 200; }

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (wordsA[i - 1].equals(wordsB[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] t = prev; prev = curr; curr = t;
            java.util.Arrays.fill(curr, 0);
        }

        return (double) prev[n] / Math.max(m, n);
    }

    /**
     * Fast trigram overlap for pre-filtering candidate windows.
     */
    private double trigramOverlap(String a, String b) {
        if (a.length() < 3 || b.length() < 3) return 0.0;

        var trigramsA = new java.util.HashSet<String>();
        for (int i = 0; i <= a.length() - 3; i++) trigramsA.add(a.substring(i, i + 3));

        int matches = 0;
        int total = 0;
        for (int i = 0; i <= b.length() - 3; i++) {
            if (trigramsA.contains(b.substring(i, i + 3))) matches++;
            total++;
        }

        return total == 0 ? 0.0 : (double) matches / total;
    }

    /**
     * Normalizes text: lowercase, remove punctuation, collapse whitespace.
     */
    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
