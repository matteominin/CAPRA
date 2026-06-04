package com.example.demo.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated audit report: issues, features, and traceability.
 */
public record AuditReport(
        String documentName,
        LocalDateTime timestamp,
        List<AuditIssue> issues,
        int totalIssues,
        Map<Severity, Long> severityDistribution,
        List<FeatureCoverage> featureCoverage,
        List<TraceabilityEntry> traceabilityMatrix
) {

    public static AuditReport from(String documentName, List<AuditIssue> issues,
                                   List<FeatureCoverage> featureCoverage,
                                   List<TraceabilityEntry> traceability) {
        Map<Severity, Long> distribution = issues.stream()
                .collect(Collectors.groupingBy(AuditIssue::severity, Collectors.counting()));
        return new AuditReport(
                documentName,
                LocalDateTime.now(),
                List.copyOf(issues),
                issues.size(),
                distribution,
                featureCoverage != null ? List.copyOf(featureCoverage) : List.of(),
                traceability != null ? List.copyOf(traceability) : List.of()
        );
    }
}
