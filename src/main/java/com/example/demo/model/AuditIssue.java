package com.example.demo.model;

/**
 * Represents a single issue detected during the document audit.
 *
 * @param id               Unique identifier (e.g. REQ-001, TST-001)
 * @param severity         Severity level
 * @param shortDescription One-sentence summary of the issue
 * @param description      Detailed description of the issue
 * @param pageReference    Page number in the document where the issue appears
 * @param quote            Verbatim quote from the original document
 * @param category         Issue category (Requirements, Architecture, Testing)
 * @param recommendation   Suggested corrective action for the student
 * @param confidenceScore  Agent confidence level (0.0-1.0). Defaults to 0.7 if not specified by the LLM.
 */
public record AuditIssue(
        String id,
        Severity severity,
        String shortDescription,
        String description,
        int pageReference,
        String quote,
        String category,
        String recommendation,
        double confidenceScore
) {
    /** Compact constructor: defaults confidence to 0.7 and generates shortDescription if missing. */
    public AuditIssue {
        if (confidenceScore <= 0.0) confidenceScore = 0.7;
        if (shortDescription == null || shortDescription.isBlank()) {
            shortDescription = description != null && description.length() > 120
                    ? description.substring(0, 117) + "..."
                    : description;
        }
    }

    /** Creates a copy with a new ID. */
    public AuditIssue withId(String newId) {
        return new AuditIssue(newId, severity, shortDescription, description, pageReference, quote, category, recommendation, confidenceScore);
    }

    /** Creates a copy with a new confidence score. */
    public AuditIssue withConfidence(double newConfidence) {
        return new AuditIssue(id, severity, shortDescription, description, pageReference, quote, category, recommendation, newConfidence);
    }
}
