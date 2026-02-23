package com.example.demo.model;

/**
 * A terminological inconsistency detected in the document.
 *
 * @param termGroup    The concept/entity the terms refer to (e.g. "System user")
 * @param variants     The different terms used (e.g. "Utente, User, Cliente, Socio")
 * @param occurrences  Approximate number of occurrences of the variants
 * @param suggestion   Suggestion on which term to use consistently
 * @param severity     MAJOR if it causes real confusion, MINOR if only stylistic
 */
public record GlossaryIssue(
        String termGroup,
        String variants,
        int occurrences,
        String suggestion,
        String severity
) {}
