package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for JSON parsing of the traceability matrix.
 */
public record TraceabilityMatrixResponse(
        List<TraceabilityEntry> entries
) {}
