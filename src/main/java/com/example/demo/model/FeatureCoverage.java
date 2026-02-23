package com.example.demo.model;

/**
 * Result of verifying a single feature in the document.
 *
 * @param featureName   Feature name (e.g. "Unit testing framework implementation")
 * @param category      Category (e.g. "Architecture", "Testing")
 * @param status        PRESENT / PARTIAL / ABSENT
 * @param coverageScore Checklist coverage percentage (0-100)
 * @param evidence      Brief evidence found in the document (or reason for absence)
 * @param matchedItems  Number of checklist items satisfied
 * @param totalItems    Total number of checklist items
 */
public record FeatureCoverage(
        String featureName,
        String category,
        FeatureStatus status,
        int coverageScore,
        String evidence,
        int matchedItems,
        int totalItems
) {
    public enum FeatureStatus {
        PRESENT, PARTIAL, ABSENT
    }
}
