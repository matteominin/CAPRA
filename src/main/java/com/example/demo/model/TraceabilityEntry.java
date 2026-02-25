package com.example.demo.model;

/**
 * A single row of the traceability matrix.
 *
 * @param useCaseId       Use case identifier (e.g. UC-1, UC-2)
 * @param useCaseName     Use case name
 * @param requirementId   Parent requirement identifier (e.g. RF-1, REQ-1). Null if no parent requirement.
 * @param requirementName Parent requirement name. Null if no parent requirement.
 * @param hasDesign       Whether a related design/architecture description exists
 * @param hasTest         Whether a related test case exists
 * @param designRef       Brief design reference (e.g. "ReservationController class")
 * @param testRef         Brief test reference (e.g. "testReserve_Success")
 * @param gap             Gap description, if any. Empty if fully covered.
 */
public record TraceabilityEntry(
        String useCaseId,
        String useCaseName,
        String requirementId,
        String requirementName,
        boolean hasDesign,
        boolean hasTest,
        String designRef,
        String testRef,
        String gap
) {}
