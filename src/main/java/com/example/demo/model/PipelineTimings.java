package com.example.demo.model;

/**
 * Per-stage timing for the audit pipeline (in seconds).
 * Used for evaluation and paper statistics (e.g. average time per stage).
 *
 * @param documentExtractionSeconds     Stage 1: PDF text extraction (Flask)
 * @param parallelAgentsSeconds         Stage 2–3: Parallel agents + traceability matrix
 * @param evidenceAndDedupSeconds       Stage 4–7: Evidence anchoring, confidence filter, consistency, normalizer
 * @param reportGenerationSeconds       Stage 8–9: LaTeX generation + PDF compilation
 */
public record PipelineTimings(
        double documentExtractionSeconds,
        double parallelAgentsSeconds,
        double evidenceAndDedupSeconds,
        double reportGenerationSeconds
) {
    public double totalSeconds() {
        return documentExtractionSeconds + parallelAgentsSeconds + evidenceAndDedupSeconds + reportGenerationSeconds;
    }
}
