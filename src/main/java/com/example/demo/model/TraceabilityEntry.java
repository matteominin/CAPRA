package com.example.demo.model;

/**
 * A single row of the traceability matrix: a Use Case / Requirement with its related links.
 *
 * @param useCaseId     Use case identifier (e.g. UC-1, UC-2) or requirement
 * @param useCaseName   Use case / requirement name
 * @param hasDesign     Whether a related design/architecture description exists
 * @param hasTest       Whether a related test case exists
 * @param designRef     Brief design reference (e.g. "ReservationController class")
 * @param testRef       Brief test reference (e.g. "testReserve_Success")
 * @param gap           Gap description, if any. Empty if fully covered.
 */
public record TraceabilityEntry(
        String useCaseId,
        String useCaseName,
        boolean hasDesign,
        boolean hasTest,
        String designRef,
        String testRef,
        String gap
) {}
