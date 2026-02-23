package com.example.demo.model;

/**
 * Issue dopo il passaggio di verifica del ConsistencyManager.
 * Include il flag di verifica e una nota esplicativa.
 */
public record VerifiedIssue(
        String id,
        Severity severity,
        String description,
        int pageReference,
        String quote,
        String category,
        String recommendation,
        boolean verified,
        String verificationNote
) {

    /**
     * Converte in AuditIssue standard (scartando i campi di verifica).
     */
    public AuditIssue toAuditIssue() {
        return new AuditIssue(id, severity, description, pageReference, quote, category, recommendation);
    }
}
