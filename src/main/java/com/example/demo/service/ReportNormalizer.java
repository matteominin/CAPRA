package com.example.demo.service;

import com.example.demo.model.AuditIssue;
import com.example.demo.model.FeatureCoverage;
import com.example.demo.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-processing utility for normalizing and validating the audit report.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Sort issues by confidence score and limit to a maximum count</li>
 *   <li>Ensure every issue has a shortDescription</li>
 *   <li>Cross-check: flag issues without verbatim quotes as generation errors</li>
 *   <li>Cross-check: warn if document mentions "security"/"transaction" keywords but
 *       no HIGH-severity issue was generated</li>
 *   <li>Filter feature coverage to only include missing/partial features</li>
 * </ul>
 */
@Service
public class ReportNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ReportNormalizer.class);

    /** Maximum number of issues to include in the final report. */
    private static final int MAX_ISSUES = 7;

    /** Maximum length for a shortDescription. */
    private static final int MAX_SHORT_DESC_LENGTH = 120;

    /** Keywords that should trigger a HIGH-severity issue if present in the document. */
    private static final List<String> CRITICAL_KEYWORDS = List.of(
            "security", "sicurezza", "transaction", "transazione",
            "authentication", "autenticazione", "authorization", "autorizzazione",
            "password", "encryption", "crittografia"
    );

    /**
     * Normalizes the list of issues: sorts by confidence, limits count,
     * ensures shortDescription, and performs cross-checks.
     *
     * @param issues       raw issues from the pipeline
     * @param documentText full document text for cross-checking
     * @return normalized issue list
     */
    public List<AuditIssue> normalizeIssues(List<AuditIssue> issues, String documentText) {
        if (issues.isEmpty()) return issues;

        // 1. Ensure every issue has a shortDescription
        List<AuditIssue> withShortDesc = issues.stream()
                .map(this::ensureShortDescription)
                .toList();

        // 2. Flag issues without verbatim quotes
        List<AuditIssue> validated = withShortDesc.stream()
                .map(this::validateQuote)
                .toList();

        // 3. Sort by confidence descending, then severity
        List<AuditIssue> sorted = validated.stream()
                .sorted(Comparator
                        .comparingDouble(AuditIssue::confidenceScore).reversed()
                        .thenComparing(i -> i.severity().ordinal()))
                .toList();

        // 4. Limit to MAX_ISSUES
        List<AuditIssue> limited;
        if (sorted.size() > MAX_ISSUES) {
            log.info("ReportNormalizer: limiting issues from {} to {}", sorted.size(), MAX_ISSUES);
            limited = sorted.subList(0, MAX_ISSUES);
        } else {
            limited = sorted;
        }

        // 5. Cross-check: warn if critical keywords present but no HIGH issue
        performCriticalKeywordCheck(limited, documentText);

        log.info("ReportNormalizer: {} issues normalized (from {} original)",
                limited.size(), issues.size());

        return limited;
    }

    /**
     * Filters feature coverage to return only PARTIAL or ABSENT features.
     * PRESENT features are excluded from the report (only missing ones are listed).
     *
     * @param features all feature coverage results
     * @return only problematic (non-PRESENT) features
     */
    public List<FeatureCoverage> filterMissingFeatures(List<FeatureCoverage> features) {
        if (features == null || features.isEmpty()) return List.of();

        List<FeatureCoverage> missing = features.stream()
                .filter(f -> f.status() != FeatureCoverage.FeatureStatus.PRESENT)
                .sorted(Comparator.comparingInt(FeatureCoverage::coverageScore))
                .toList();

        log.info("ReportNormalizer: {}/{} features are missing or partial",
                missing.size(), features.size());

        return missing;
    }

    /**
     * Computes extraction completeness metrics from the issues.
     *
     * @param issues     all issues
     * @param features   feature coverage list
     * @return map of metric name → status description
     */
    public Map<String, String> computeExtractionCompleteness(List<AuditIssue> issues,
                                                              List<FeatureCoverage> features) {
        Map<String, String> metrics = new LinkedHashMap<>();

        // Requirements completeness
        long reqIssues = issues.stream()
                .filter(i -> "Requirements".equalsIgnoreCase(i.category()))
                .count();
        metrics.put("Requirements", reqIssues == 0 ? "COMPLETE" :
                reqIssues + " issue(s) found");

        // Architecture completeness
        long archIssues = issues.stream()
                .filter(i -> "Architecture".equalsIgnoreCase(i.category()))
                .count();
        metrics.put("Architecture", archIssues == 0 ? "COMPLETE" :
                archIssues + " issue(s) found");

        // Testing completeness
        long testIssues = issues.stream()
                .filter(i -> "Testing".equalsIgnoreCase(i.category()))
                .count();
        metrics.put("Testing", testIssues == 0 ? "COMPLETE" :
                testIssues + " issue(s) found");

        // Feature coverage
        if (features != null && !features.isEmpty()) {
            long present = features.stream()
                    .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
            int avgCoverage = (int) features.stream()
                    .mapToInt(FeatureCoverage::coverageScore).average().orElse(0);
            metrics.put("Feature Coverage", "%d/%d present (%d%% avg)".formatted(
                    present, features.size(), avgCoverage));
        }

        return metrics;
    }

    // ═══════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════

    private AuditIssue ensureShortDescription(AuditIssue issue) {
        if (issue.shortDescription() != null && !issue.shortDescription().isBlank()) {
            // Truncate if too long
            if (issue.shortDescription().length() > MAX_SHORT_DESC_LENGTH) {
                String truncated = issue.shortDescription().substring(0, MAX_SHORT_DESC_LENGTH - 3) + "...";
                return new AuditIssue(issue.id(), issue.severity(), truncated,
                        issue.description(), issue.pageReference(), issue.quote(),
                        issue.category(), issue.recommendation(), issue.confidenceScore());
            }
            return issue;
        }

        // Generate from description
        String shortDesc = issue.description();
        if (shortDesc != null && shortDesc.length() > MAX_SHORT_DESC_LENGTH) {
            // Cut at last period before limit
            int lastPeriod = shortDesc.lastIndexOf('.', MAX_SHORT_DESC_LENGTH);
            if (lastPeriod > MAX_SHORT_DESC_LENGTH / 2) {
                shortDesc = shortDesc.substring(0, lastPeriod + 1);
            } else {
                shortDesc = shortDesc.substring(0, MAX_SHORT_DESC_LENGTH - 3) + "...";
            }
        }

        return new AuditIssue(issue.id(), issue.severity(), shortDesc,
                issue.description(), issue.pageReference(), issue.quote(),
                issue.category(), issue.recommendation(), issue.confidenceScore());
    }

    private AuditIssue validateQuote(AuditIssue issue) {
        if (issue.quote() == null || issue.quote().isBlank()) {
            log.warn("ReportNormalizer: issue {} has no quote — generation error", issue.id());
            // Penalize confidence for quoteless issues
            return issue.withConfidence(issue.confidenceScore() * 0.6);
        }
        return issue;
    }

    private void performCriticalKeywordCheck(List<AuditIssue> issues, String documentText) {
        if (documentText == null) return;

        String lowerDoc = documentText.toLowerCase();
        boolean hasCriticalKeyword = CRITICAL_KEYWORDS.stream()
                .anyMatch(lowerDoc::contains);

        if (!hasCriticalKeyword) return;

        boolean hasHighIssue = issues.stream()
                .anyMatch(i -> i.severity() == Severity.HIGH);

        if (!hasHighIssue) {
            // Info-level only: the presence of security keywords in a well-handled
            // document should NOT force HIGH-severity issues.
            String foundKeywords = CRITICAL_KEYWORDS.stream()
                    .filter(lowerDoc::contains)
                    .collect(Collectors.joining(", "));
            log.debug("ReportNormalizer: document mentions [{}] — no HIGH issue generated, " +
                    "which is acceptable if those areas are properly addressed.", foundKeywords);
        }
    }
}
