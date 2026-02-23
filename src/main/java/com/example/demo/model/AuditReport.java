package com.example.demo.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Report aggregato dell'audit contenente tutti i problemi verificati e la copertura delle feature.
 */
public record AuditReport(
        String documentName,
        LocalDateTime timestamp,
        List<AuditIssue> issues,
        int totalIssues,
        Map<Severity, Long> severityDistribution,
        List<FeatureCoverage> featureCoverage
) {

    /**
     * Factory method che calcola automaticamente le statistiche di distribuzione.
     */
    public static AuditReport from(String documentName, List<AuditIssue> issues,
                                   List<FeatureCoverage> featureCoverage) {
        Map<Severity, Long> distribution = issues.stream()
                .collect(Collectors.groupingBy(AuditIssue::severity, Collectors.counting()));
        return new AuditReport(
                documentName,
                LocalDateTime.now(),
                List.copyOf(issues),
                issues.size(),
                distribution,
                featureCoverage != null ? List.copyOf(featureCoverage) : List.of()
        );
    }
}
