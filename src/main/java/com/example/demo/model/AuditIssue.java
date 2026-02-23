package com.example.demo.model;

/**
 * Rappresenta un singolo problema rilevato durante l'audit del documento.
 *
 * @param id              Identificativo univoco (es. REQ-001, TST-001)
 * @param severity        Livello di gravità
 * @param description     Descrizione dettagliata del problema
 * @param pageReference   Numero di pagina del documento dove appare il problema
 * @param quote           Citazione testuale dal documento originale
 * @param category        Categoria del problema (Requisiti, Architettura, Testing)
 * @param recommendation  Azione correttiva suggerita allo studente
 * @param confidenceScore Livello di fiducia dell'agente (0.0-1.0). Default 0.8 se non specificato dall'LLM.
 */
public record AuditIssue(
        String id,
        Severity severity,
        String description,
        int pageReference,
        String quote,
        String category,
        String recommendation,
        double confidenceScore
) {
    /** Compact constructor: default confidence 0.8 se non parsato. */
    public AuditIssue {
        if (confidenceScore <= 0.0) confidenceScore = 0.8;
    }

    /** Crea una copia con un nuovo ID. */
    public AuditIssue withId(String newId) {
        return new AuditIssue(newId, severity, description, pageReference, quote, category, recommendation, confidenceScore);
    }

    /** Crea una copia con un nuovo confidence score. */
    public AuditIssue withConfidence(double newConfidence) {
        return new AuditIssue(id, severity, description, pageReference, quote, category, recommendation, newConfidence);
    }
}
