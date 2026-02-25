package com.example.demo.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated audit report: issues, features, traceability, glossary,
 * extracted use cases, and extracted requirements.
 */
public record AuditReport(
        String documentName,
        LocalDateTime timestamp,
        List<AuditIssue> issues,
        int totalIssues,
        Map<Severity, Long> severityDistribution,
        List<FeatureCoverage> featureCoverage,
        List<TraceabilityEntry> traceabilityMatrix,
        List<GlossaryIssue> glossaryIssues,
        List<UseCaseEntry> useCases,
        List<RequirementEntry> requirements
) {

    public static AuditReport from(String documentName, List<AuditIssue> issues,
                                   List<FeatureCoverage> featureCoverage,
                                   List<TraceabilityEntry> traceability,
                                   List<GlossaryIssue> glossary,
                                   List<UseCaseEntry> useCases,
                                   List<RequirementEntry> requirements) {
        Map<Severity, Long> distribution = issues.stream()
                .collect(Collectors.groupingBy(AuditIssue::severity, Collectors.counting()));
        return new AuditReport(
                documentName,
                LocalDateTime.now(),
                List.copyOf(issues),
                issues.size(),
                distribution,
                featureCoverage != null ? List.copyOf(featureCoverage) : List.of(),
                traceability != null ? List.copyOf(traceability) : List.of(),
                glossary != null ? List.copyOf(glossary) : List.of(),
                useCases != null ? List.copyOf(useCases) : List.of(),
                requirements != null ? List.copyOf(requirements) : List.of()
        );
    }
}
