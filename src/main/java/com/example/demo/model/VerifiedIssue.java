package com.example.demo.model;

/**
 * Issue after the ConsistencyManager verification pass.
 * Includes the verification flag and an explanatory note.
 */
public record VerifiedIssue(
        String id,
        Severity severity,
        String shortDescription,
        String description,
        int pageReference,
        String quote,
        String category,
        String recommendation,
        double confidenceScore,
        boolean verified,
        String verificationNote
) {

    /**
     * Converts to a standard AuditIssue (discarding verification fields).
     */
    public AuditIssue toAuditIssue() {
        return new AuditIssue(id, severity, shortDescription, description, pageReference, quote, category, recommendation, confidenceScore);
    }
}
