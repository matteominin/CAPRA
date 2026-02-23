package com.example.demo.model;

/**
 * Risultato della verifica di una singola feature nel documento.
 *
 * @param featureName   Nome della feature (es. "Unit testing framework implementation")
 * @param category      Categoria (es. "Architecture", "Testing")  
 * @param status        PRESENT / PARTIAL / ABSENT
 * @param coverageScore Percentuale di copertura della checklist (0-100)
 * @param evidence      Breve evidenza trovata nel documento (o motivo dell'assenza)
 * @param matchedItems  Numero di voci della checklist soddisfatte
 * @param totalItems    Numero totale di voci nella checklist
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
