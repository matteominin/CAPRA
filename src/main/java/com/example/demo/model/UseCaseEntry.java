package com.example.demo.model;

/**
 * A single use case extracted from the document.
 *
 * @param useCaseId        Use case identifier exactly as in the document (e.g. UC-1, UC-0.1)
 * @param useCaseName      Use case name (e.g. "Registrazione", "Effettua prestito")
 * @param actor            Primary actor, if specified in a template. Null if no template.
 * @param preconditions    Preconditions text. Null if no template.
 * @param mainFlow         Main flow (numbered steps as a single string). Null if no template.
 * @param postconditions   Postconditions text. Null if no template.
 * @param alternativeFlows Alternative/exception flows. Null if none.
 * @param hasTemplate      True if the document provides a structured template for this UC.
 */
public record UseCaseEntry(
        String useCaseId,
        String useCaseName,
        String actor,
        String preconditions,
        String mainFlow,
        String postconditions,
        String alternativeFlows,
        boolean hasTemplate
) {}
