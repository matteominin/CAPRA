package com.example.demo.model;

/**
 * A single functional requirement extracted from the document.
 *
 * @param requirementId   Requirement identifier exactly as in the document (e.g. RF-1, REQ-01, R1)
 * @param requirementName Requirement name/title (e.g. "Registrazione utente")
 */
public record RequirementEntry(
        String requirementId,
        String requirementName
) {}
