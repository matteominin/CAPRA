package com.example.demo.model;

import java.util.List;

/**
 * Wrapper per il parsing JSON della matrice di tracciabilità.
 */
public record TraceabilityMatrixResponse(
        List<TraceabilityEntry> entries
) {}
