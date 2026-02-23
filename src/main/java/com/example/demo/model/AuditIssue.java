package com.example.demo.model;

/**
 * Rappresenta un singolo problema rilevato durante l'audit del documento.
 *
 * @param id             Identificativo univoco (es. REQ-001, TST-001)
 * @param severity       Livello di gravità
 * @param description    Descrizione dettagliata del problema
 * @param pageReference  Numero di pagina del documento dove appare il problema
 * @param quote          Citazione testuale dal documento originale
 * @param category       Categoria del problema (Requisiti, Architettura, Testing)
 * @param recommendation Azione correttiva suggerita allo studente
 */
public record AuditIssue(
        String id,
        Severity severity,
        String description,
        int pageReference,
        String quote,
        String category,
        String recommendation
) {
    /** Crea una copia con un nuovo ID. */
    public AuditIssue withId(String newId) {
        return new AuditIssue(newId, severity, description, pageReference, quote, category, recommendation);
    }
}
