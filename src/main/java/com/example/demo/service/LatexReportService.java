package com.example.demo.service;

import com.example.demo.config.AuditProperties;
import com.example.demo.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates the audit report in LaTeX format.
 * Uses Haiku 4.5 (Anthropic) for narrative sections with fallback to GPT-5.1 (OpenAI).
 * <p>
 * Report structure:
 * 1. Project Overview (what the project does + inline stats)
 * 2. Requirements Analysis (requirement listing with UC linkage)
 * 3. Architecture Analysis (LLM description + issues)
 * 4. Use Case Analysis (all UCs split by template/no-template, templates as tables)
 * 5. Testing Analysis (strategy + additional test issues)
 * 6. Traceability Analysis (Req -> UC -> Design -> Test matrix)
 * 7. Missing Features (centered table + details)
 */
@Service
public class LatexReportService {

    private static final Logger log = LoggerFactory.getLogger(LatexReportService.class);

    private final ChatClient reportChatClient;   // Anthropic Haiku 4.5
    private final ChatClient analysisChatClient;  // OpenAI GPT-5.1 (fallback)
    private final AuditProperties properties;

    public LatexReportService(@Qualifier("reportChatClient") ChatClient reportChatClient,
                              @Qualifier("analysisChatClient") ChatClient analysisChatClient,
                              AuditProperties properties) {
        this.reportChatClient = reportChatClient;
        this.analysisChatClient = analysisChatClient;
        this.properties = properties;
    }

    /**
     * Generates the complete LaTeX report.
     *
     * @param report                 aggregated audit report
     * @param fullText               original document text
     * @param missingFeatures        only PARTIAL/ABSENT features (pre-filtered)
     * @param allFeatures            all features (for metrics)
     * @param extractionCompleteness metrics from ReportNormalizer
     * @return path to the generated .tex file
     */
    public Path generateReport(AuditReport report, String fullText,
                                List<FeatureCoverage> missingFeatures,
                                List<FeatureCoverage> allFeatures,
                                Map<String, String> extractionCompleteness) {
        log.info("Generating LaTeX report for '{}' ({} issues, {} missing features)",
                report.documentName(), report.totalIssues(), missingFeatures.size());

        String summaryContent = generateSummarySection(report, allFeatures, extractionCompleteness);
        String latexContent = buildLatexDocument(report, summaryContent, missingFeatures,
                allFeatures, extractionCompleteness, fullText);

        try {
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path projectReports = Path.of(properties.latex().outputDir()).resolve(timestamp);
            Files.createDirectories(projectReports);
            Path texFile = projectReports.resolve("audit-report.tex");
            Files.writeString(texFile, latexContent, StandardCharsets.UTF_8);
            log.info("LaTeX file generated: {}", texFile);
            return texFile;
        } catch (IOException e) {
            throw new RuntimeException("Error writing the LaTeX file: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════
    // LLM-generated sections
    // ═══════════════════════════════════════════════════

    private String generateSummarySection(AuditReport report,
                                           List<FeatureCoverage> allFeatures,
                                           Map<String, String> extractionCompleteness) {
        if (report.issues().isEmpty()) {
            return "The document analysis did not reveal any significant issues. "
                    + "The document appears well-structured and coherent.";
        }

        Map<String, List<AuditIssue>> byCategory = report.issues().stream()
                .collect(Collectors.groupingBy(i -> i.category() != null ? i.category() : "Other",
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder issuesSummary = new StringBuilder();
        for (var entry : byCategory.entrySet()) {
            issuesSummary.append("\nCategory %s (%d issues):\n".formatted(entry.getKey(), entry.getValue().size()));
            for (AuditIssue i : entry.getValue()) {
                issuesSummary.append("- [%s] %s: %s (p. %d)\n".formatted(
                        i.severity(), i.id(),
                        i.shortDescription() != null ? i.shortDescription() : i.description(),
                        i.pageReference()));
            }
        }

        StringBuilder featureSummary = new StringBuilder();
        if (allFeatures != null && !allFeatures.isEmpty()) {
            long present = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
            long partial = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
            long absent = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();
            featureSummary.append("\nFeature coverage: %d present, %d partial, %d absent (out of %d)\n"
                    .formatted(present, partial, absent, allFeatures.size()));
        }

        String systemPrompt = """
            You are a Software Engineering expert. Generate a PROJECT OVERVIEW in English
            for an audit report of a SWE document written by a university student.
            Generate the output directly in valid LaTeX code.
                
            YOUR TASK: Describe ONLY what the project is about.
            Write a concise description of the system the student designed:
            - What is the application domain?
            - What are the main features and functionalities?
            - What technologies and frameworks are used?
            - What architectural patterns are mentioned?
                
            DO NOT discuss the quality of the document.
            DO NOT mention audit findings, issues, or recommendations.
            DO NOT say things like "this document" or "this software engineering document".
            Instead, refer to "the project" or use the project/system name directly.
                
            OUTPUT FORMAT (pure LaTeX):
            - One or two short paragraphs
            - Use \\textbf{} for emphasis on key technologies and patterns
            - Properly escape: & as \\&, % as \\%, _ as \\_
            - DO NOT generate \\documentclass, \\begin{document}, \\section, \\subsection
            - DO NOT use non-standard packages
            - DO NOT use Unicode characters or special symbols: use only ASCII characters and standard LaTeX commands
            - COMPLETE the entire description: do not stop halfway
            - Maximum 200 words total
            - Use a formal, professional, and objective tone
            """;

        String userPrompt = """
            Generate the project overview for the document '%s'.
            Total issues found: %d (HIGH: %d, MEDIUM: %d, LOW: %d)
            %s
            %s""".formatted(
                report.documentName(),
                report.totalIssues(),
                report.severityDistribution().getOrDefault(Severity.HIGH, 0L),
                report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L),
                report.severityDistribution().getOrDefault(Severity.LOW, 0L),
                issuesSummary,
                featureSummary);

        try {
            String summary = callLlmWithFallback(systemPrompt, userPrompt);
            log.debug("Summary generated ({} characters)", summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("Unable to generate summary: {}", e.getMessage());
            return ("The analysis of document '%s' found %d issues, " +
                    "of which %d are high severity. A thorough review of the critical areas is recommended.").formatted(
                    report.documentName(),
                    report.totalIssues(),
                    report.severityDistribution().getOrDefault(Severity.HIGH, 0L));
        }
    }

    private String callLlmWithFallback(String systemPrompt, String userPrompt) {
        try {
            org.springframework.ai.chat.model.ChatResponse chatResponse = reportChatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            captureTokens(chatResponse, true);
            String result = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText() : null;
            if (result != null && !result.isBlank()) {
                return result;
            }
            log.warn("Anthropic returned empty response, trying OpenAI...");
        } catch (Exception e) {
            log.warn("Anthropic failed ({}), trying OpenAI...", e.getMessage());
        }

        org.springframework.ai.chat.model.ChatResponse chatResponse = analysisChatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();
        captureTokens(chatResponse, false);
        String result = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText() : null;
        return result != null ? result : "";
    }

    private void captureTokens(org.springframework.ai.chat.model.ChatResponse chatResponse,
                                boolean isAnthropic) {
        if (chatResponse == null) return;
        try {
            var metadata = chatResponse.getMetadata();
            if (metadata == null) return;
            var usage = metadata.getUsage();
            if (usage == null) return;
            long input  = usage.getPromptTokens()     != null ? usage.getPromptTokens().longValue()     : 0L;
            long output = usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0L;
            if (input == 0 && output == 0) return;
            TokenUsageAccumulator acc = TokenUsageAccumulator.current();
            if (acc == null) return;
            if (isAnthropic) {
                acc.addAnthropicTokens(input, output);
            } else {
                acc.addOpenAiTokens(input, output);
            }
        } catch (Exception e) {
            log.debug("LatexReportService: failed to capture token usage — {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // LaTeX document building — NEW TEMPLATE
    // ═══════════════════════════════════════════════════

    private String buildLatexDocument(AuditReport report, String summary,
                                      List<FeatureCoverage> missingFeatures,
                                      List<FeatureCoverage> allFeatures,
                                      Map<String, String> extractionCompleteness,
                                      String fullText) {
        var sb = new StringBuilder();

        // ── Preamble ──
        sb.append("""
            \\documentclass[11pt,a4paper]{scrartcl}
            \\usepackage[utf8]{inputenc}
            \\usepackage[T1]{fontenc}
            \\usepackage[english]{babel}
            \\usepackage{ragged2e}
            \\usepackage{hyperref}
            \\usepackage{enumitem}
            \\usepackage{booktabs}
            \\usepackage{geometry}
            \\usepackage{fancyhdr}
            \\usepackage{longtable}
            \\usepackage{array}
            \\usepackage{amssymb}
            \\geometry{a4paper, margin=2.5cm}
            \\setlength{\\emergencystretch}{3em}
                
            \\hypersetup{
              colorlinks=true,
              linkcolor=blue!70!black,
              urlcolor=blue!70!black,
            }
                
            \\newcommand{\\ok}{$\\checkmark$}
            \\newcommand{\\nok}{$\\times$}
                
            \\pagestyle{fancy}
            \\fancyhf{}
            \\fancyhead[L]{\\small CAPRA --- Software Engineering Audit}
            \\fancyhead[R]{\\small \\today}
            \\fancyfoot[C]{\\thepage}
                
            \\title{Software Engineering Audit Report\\\\[0.3em]\\large %s}
            \\author{CAPRA --- Automated Audit System}
            \\date{\\today}
            \\begin{document}
            \\maketitle
            \\tableofcontents
            \\newpage
            """.formatted(escapeLatex(report.documentName())));

        // ── 1. Project Overview ──
        sb.append("\\section{Project Overview}\n");
        sb.append("\\label{sec:overview}\n");
        sb.append(buildSummaryWithMetrics(report, summary, allFeatures, extractionCompleteness));
        sb.append("\n");

        // ── 2. Requirements Analysis ──
        sb.append("\\section{Requirements Analysis}\n");
        sb.append("\\label{sec:requirements}\n");
        sb.append(buildRequirementsSection(report));
        sb.append("\n");

        // ── 3. Architecture Analysis ──
        sb.append("\\section{Architecture Analysis}\n");
        sb.append("\\label{sec:architecture}\n");
        sb.append(buildArchitectureSection(report, fullText));
        sb.append("\n");

        // ── 4. Use Case Analysis ──
        sb.append("\\section{Use Case Analysis}\n");
        sb.append("\\label{sec:usecases}\n");
        sb.append(buildUseCaseSection(report, fullText));
        sb.append("\n");

        // ── 5. Testing Analysis ──
        sb.append("\\section{Testing Analysis}\n");
        sb.append("\\label{sec:testing}\n");
        sb.append(buildTestingSection(report, fullText));
        sb.append("\n");

        // ── 6. Traceability Analysis ──
        sb.append("\\section{Traceability Analysis}\n");
        sb.append("\\label{sec:traceability}\n");
        sb.append(buildTraceabilitySection(report));
        sb.append("\n");

        // ── 7. Missing Features ──
        sb.append("\\section{Missing Features}\n");
        sb.append(buildMissingFeaturesSection(missingFeatures, allFeatures));
        sb.append("\n");

        sb.append("\\end{document}\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // Section builders
    // ═══════════════════════════════════════════════════

    /**
     * Section 1: Project Overview — LLM project description + inline stats line.
     */
    private String buildSummaryWithMetrics(AuditReport report, String summaryText,
                                            List<FeatureCoverage> allFeatures,
                                            Map<String, String> extractionCompleteness) {
        var sb = new StringBuilder();

        // ── LLM narrative text ──
        sb.append(sanitizeLlmLatex(summaryText));
        sb.append("\n\n");

        // ── Inline stats line ──
        long high = report.severityDistribution().getOrDefault(Severity.HIGH, 0L);
        long med = report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L);
        long low = report.severityDistribution().getOrDefault(Severity.LOW, 0L);

        sb.append("\\noindent\\rule{\\textwidth}{0.4pt}\\\\\n");
        sb.append("{\\small Issues found: \\textbf{%d} (HIGH: %d, MEDIUM: %d, LOW: %d)".formatted(
                report.totalIssues(), high, med, low));

        if (allFeatures != null && !allFeatures.isEmpty()) {
            long present = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
            long partial = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
            long absent = allFeatures.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();
            sb.append(" \\quad---\\quad Features: %d/%d present".formatted(present, allFeatures.size()));
            if (partial > 0) sb.append(", %d partial".formatted(partial));
            if (absent > 0) sb.append(", %d absent".formatted(absent));
        }
        sb.append("}\n\n");

        return sb.toString();
    }

    /**
     * Section 6: Missing Features — centered summary table + paragraph details.
     */
    private String buildMissingFeaturesSection(List<FeatureCoverage> missingFeatures,
                                                List<FeatureCoverage> allFeatures) {
        var sb = new StringBuilder();

        if (allFeatures == null || allFeatures.isEmpty()) {
            sb.append("No feature checklist was configured for this audit.\n\n");
            return sb.toString();
        }

        long totalPresent = allFeatures.stream()
                .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();

        if (missingFeatures.isEmpty()) {
            sb.append("All %d expected features are fully present in the document. No missing features detected.\n\n"
                    .formatted(allFeatures.size()));
            return sb.toString();
        }

        // ── Centered summary table ──
        sb.append("\\begin{center}\n");
        sb.append("\\begin{tabular}{>{\\RaggedRight\\arraybackslash}p{6cm} c c}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Feature} & \\textbf{Status} & \\textbf{Coverage} \\\\\n");
        sb.append("\\midrule\n");

        for (FeatureCoverage f : missingFeatures) {
            String statusLabel = f.status() == FeatureCoverage.FeatureStatus.PARTIAL ? "partial" : "absent";
            sb.append("%s & \\textsc{%s} & %d\\%% \\\\\n".formatted(
                    escapeLatex(f.featureName()), statusLabel, f.coverageScore()));
        }
        if (totalPresent > 0) {
            sb.append("\\textit{%d other feature(s)} & \\textsc{present} & 100\\%% \\\\\n"
                    .formatted(totalPresent));
        }
        sb.append("\\bottomrule\n");
        sb.append("\\end{tabular}\n");
        sb.append("\\end{center}\n\n");

        // ── Details for each problematic feature ──
        for (FeatureCoverage f : missingFeatures) {
            sb.append("\\paragraph{%s}\n".formatted(escapeLatex(f.featureName())));
            if (f.evidence() != null && !f.evidence().isBlank()) {
                sb.append("%s ".formatted(escapeLatex(f.evidence())));
            } else {
                sb.append("This feature is %s in the document. ".formatted(
                        f.status() == FeatureCoverage.FeatureStatus.PARTIAL
                                ? "only partially covered" : "not present"));
            }
            String suggestion = f.status() == FeatureCoverage.FeatureStatus.PARTIAL
                    ? "Expand the existing coverage to address the missing aspects."
                    : "Add a dedicated section that addresses this feature.";
            sb.append("$\\rightarrow$ %s\n\n".formatted(escapeLatex(suggestion)));
        }

        return sb.toString();
    }

    /**
     * Section 2: Requirements Analysis — table listing requirements
     * with their UC linkage status. Requirements without UCs are flagged.
     */
    private String buildRequirementsSection(AuditReport report) {
        var sb = new StringBuilder();

        if (report.traceabilityMatrix() == null || report.traceabilityMatrix().isEmpty()) {
            sb.append("No requirements or use cases could be traced in the document.\n\n");
            return sb.toString();
        }

        List<TraceabilityEntry> entries = report.traceabilityMatrix();

        // Group entries by requirementId to build the requirements table
        // A requirement may link to multiple UCs
        Map<String, List<TraceabilityEntry>> byReq = new LinkedHashMap<>();
        List<TraceabilityEntry> orphanUCs = new ArrayList<>(); // UCs without a parent req

        for (TraceabilityEntry e : entries) {
            if (e.requirementId() != null && !e.requirementId().isBlank()) {
                byReq.computeIfAbsent(e.requirementId(), k -> new ArrayList<>()).add(e);
            } else if (e.useCaseId() != null && !e.useCaseId().isBlank()) {
                orphanUCs.add(e); // UC without parent requirement - not shown here
            } else {
                // Requirement with no UC (useCaseId is null)
                byReq.computeIfAbsent(e.requirementId() != null ? e.requirementId() : "UNKNOWN",
                        k -> new ArrayList<>()).add(e);
            }
        }

        sb.append("\\textit{This section lists the functional requirements identified in the document ");
        sb.append("and indicates whether each has an associated use case.}\n\n");

        // Build requirements table
        sb.append("\\begin{center}\n");
        sb.append("\\begin{longtable}{l >{\\RaggedRight\\arraybackslash}p{4.5cm} c >{\\RaggedRight\\arraybackslash}p{4.5cm}}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Req.} & \\textbf{Name} & \\textbf{UC?} & \\textbf{Linked UCs} \\\\\n");
        sb.append("\\midrule\n");
        sb.append("\\endhead\n");

        List<String> reqsWithoutUC = new ArrayList<>();

        for (var entry : byReq.entrySet()) {
            String reqId = entry.getKey();
            List<TraceabilityEntry> reqEntries = entry.getValue();

            // Get requirement name from any entry
            String reqName = reqEntries.stream()
                    .filter(e -> e.requirementName() != null && !e.requirementName().isBlank())
                    .map(TraceabilityEntry::requirementName)
                    .findFirst().orElse("");

            // Check if any entry has a UC
            List<String> linkedUCs = reqEntries.stream()
                    .filter(e -> e.useCaseId() != null && !e.useCaseId().isBlank())
                    .map(e -> "\\hyperlink{uc:%s}{%s}".formatted(e.useCaseId(), e.useCaseId()))
                    .toList();

            boolean hasUC = !linkedUCs.isEmpty();
            String ucMarker = hasUC ? "\\ok" : "\\nok";
            String ucList = hasUC ? String.join(", ", linkedUCs) : "---";

            sb.append("\\hypertarget{req:%s}{%s} & %s & %s & %s \\\\\n".formatted(
                    reqId, escapeLatex(reqId), escapeLatex(reqName), ucMarker, ucList));

            if (!hasUC) {
                reqsWithoutUC.add(reqId);
            }
        }

        sb.append("\\bottomrule\n");
        sb.append("\\end{longtable}\n");
        sb.append("\\end{center}\n\n");

        // Requirements without UC — details
        if (!reqsWithoutUC.isEmpty()) {
            sb.append("\\paragraph{Requirements without UCs:}\n");
            sb.append("\\begin{itemize}[leftmargin=*]\n");
            for (String reqId : reqsWithoutUC) {
                List<TraceabilityEntry> reqEntries = byReq.get(reqId);
                String reqName = reqEntries.stream()
                        .filter(e -> e.requirementName() != null && !e.requirementName().isBlank())
                        .map(TraceabilityEntry::requirementName)
                        .findFirst().orElse("");
                String gap = reqEntries.stream()
                        .filter(e -> e.gap() != null && !e.gap().isBlank())
                        .map(TraceabilityEntry::gap)
                        .findFirst().orElse("No associated use case found.");
                sb.append("  \\item \\textbf{\\hyperlink{req:%s}{%s}".formatted(reqId, escapeLatex(reqId)));
                if (!reqName.isBlank()) {
                    sb.append(" --- %s".formatted(escapeLatex(reqName)));
                }
                sb.append(":} %s $\\rightarrow$ Define a UC or mark as out of scope.\n".formatted(
                        escapeLatex(gap)));
            }
            sb.append("\\end{itemize}\n\n");
        }

        return sb.toString();
    }

    /**
     * Section 4: Use Cases — all UCs shown, split by with/without template.
     * UCs with templates are rendered as centered tables.
     * Internal structural issues appear below each UC table.
     */
    private String buildUseCaseSection(AuditReport report, String fullText) {
        var sb = new StringBuilder();

        // Collect issues that reference specific UCs (exclude Testing and Architecture)
        Map<String, List<AuditIssue>> issuesByUC = new LinkedHashMap<>();
        for (AuditIssue issue : report.issues()) {
            if ("Testing".equalsIgnoreCase(issue.category())
                    || "Architecture".equalsIgnoreCase(issue.category())) {
                continue;
            }
            Set<String> ucs = extractAllUCs(issue);
            for (String uc : ucs) {
                issuesByUC.computeIfAbsent(uc, k -> new ArrayList<>()).add(issue);
            }
        }

        // Build list of all UCs from traceability matrix
        Set<String> allUCs = new LinkedHashSet<>();
        if (report.traceabilityMatrix() != null) {
            for (TraceabilityEntry e : report.traceabilityMatrix()) {
                if (e.useCaseId() != null && !e.useCaseId().isBlank()) {
                    allUCs.add(e.useCaseId());
                }
            }
        }
        allUCs.addAll(issuesByUC.keySet());

        if (allUCs.isEmpty()) {
            sb.append("No use cases were identifiable in the document.\n\n");
            return sb.toString();
        }

        sb.append("The document describes \\textbf{%d use case(s)}. ".formatted(allUCs.size()));
        sb.append("This section analyzes the \\emph{internal quality} of each use case ");
        sb.append("(completeness, clarity, consistency of the template). ");
        sb.append("Traceability to requirements and tests is analyzed in Section~\\ref{sec:traceability}.\n\n");

        // Determine which UCs have templates (have issues referencing them, meaning they were analyzed in detail)
        // UCs with issues are considered "with template" since the agent found structural details to analyze
        Set<String> ucsWithIssues = new LinkedHashSet<>(issuesByUC.keySet());

        // ── UCs with structured template (those that have issues = analyzed in detail) ──
        if (!ucsWithIssues.isEmpty()) {
            sb.append("\\subsection{Use Cases with Structured Template}\n\n");

            for (String ucId : allUCs) {
                List<AuditIssue> ucIssues = issuesByUC.getOrDefault(ucId, List.of());
                if (ucIssues.isEmpty()) continue;

                String ucName = findUcName(report, ucId);
                String heading = ucName.isBlank() ? ucId : "%s --- %s".formatted(ucId, ucName);

                // Render UC template as centered table
                sb.append("\\begin{center}\n");
                sb.append("\\hypertarget{uc:%s}{}\n".formatted(ucId));
                sb.append("\\begin{tabular}{l >{\\RaggedRight\\arraybackslash}p{10cm}}\n");
                sb.append("\\toprule\n");
                sb.append("\\multicolumn{2}{l}{\\textbf{%s}} \\\\\n".formatted(escapeLatex(heading)));
                sb.append("\\bottomrule\n");
                sb.append("\\end{tabular}\n");
                sb.append("\\end{center}\n\n");

                // Issues below the table
                sb.append(buildUcIssueList(ucIssues));
            }
        }

        // ── UCs without structured template ──
        List<String> ucsWithoutTemplate = allUCs.stream()
                .filter(uc -> !ucsWithIssues.contains(uc))
                .toList();

        if (!ucsWithoutTemplate.isEmpty()) {
            sb.append("\\subsection{Use Cases without Structured Template}\n");
            sb.append("The following use cases are referenced in diagrams or narrative but lack ");
            sb.append("a formal template description. A missing template does not necessarily ");
            sb.append("indicate an error if the UC is sufficiently simple.\n\n");
            sb.append("\\begin{itemize}[nosep]\n");
            for (String ucId : ucsWithoutTemplate) {
                String ucName = findUcName(report, ucId);
                String label = ucName.isBlank() ? ucId : "%s --- %s".formatted(ucId, ucName);
                sb.append("  \\item \\hypertarget{uc:%s}{%s}\n".formatted(ucId, escapeLatex(label)));
            }
            sb.append("\\end{itemize}\n\n");
        }

        return sb.toString();
    }

    /**
     * Renders a schematic list of issues for a single UC: problem + suggestion.
     */
    private String buildUcIssueList(List<AuditIssue> issues) {
        var sb = new StringBuilder();
        sb.append("\\begin{description}[style=nextline, leftmargin=1em]\n");
        for (AuditIssue issue : issues) {
            String sevBadge = severityBadge(issue.severity());
            sb.append("  \\item[%s --- %s]\n".formatted(
                    escapeLatex(issue.id()), sevBadge));

            // Problem description (schematic)
            sb.append("  \\textbf{Problem:} %s\n".formatted(
                    escapeLatex(issue.shortDescription() != null ? issue.shortDescription() : issue.description())));

            // Suggestion
            if (issue.recommendation() != null && !issue.recommendation().isBlank()) {
                sb.append("\n  \\textbf{Suggestion:} %s\n".formatted(
                        escapeLatex(issue.recommendation())));
            }
            sb.append("\n");
        }
        sb.append("\\end{description}\n\n");
        return sb.toString();
    }

    /**
     * Section 5: Testing — strategy overview + additional non-UC test issues.
     * Missing UC test coverage is shown in the Traceability section.
     */
    private String buildTestingSection(AuditReport report, String fullText) {
        var sb = new StringBuilder();

        // Generate testing strategy description via LLM
        String testStrategy = generateTestingStrategyOverview(fullText);
        sb.append("\\subsection{Testing Strategy}\n");
        sb.append(sanitizeLlmLatex(testStrategy));
        sb.append("\n\n");

        // Additional test issues from TestAuditorAgent (not UC-specific)
        List<AuditIssue> testIssues = report.issues().stream()
                .filter(i -> "Testing".equalsIgnoreCase(i.category()))
                .filter(i -> extractAllUCs(i).isEmpty())
                .toList();

        if (!testIssues.isEmpty()) {
            sb.append("\\subsection{Additional Testing Issues}\n\n");
            sb.append("\\begin{description}[style=nextline, leftmargin=1em]\n");
            for (AuditIssue issue : testIssues) {
                String sevBadge = severityBadge(issue.severity());
                sb.append("  \\item[%s --- %s --- \\hypertarget{tst:%s}{}%s]\n".formatted(
                        sevBadge, escapeLatex(issue.id()), issue.id(),
                        issue.shortDescription() != null ? " " + escapeLatex(issue.shortDescription()) : ""));

                sb.append("  \\textbf{Problem:} %s\n".formatted(
                        escapeLatex(issue.description())));

                if (issue.recommendation() != null && !issue.recommendation().isBlank()) {
                    sb.append("\n  \\textbf{Suggestion:} %s\n".formatted(
                            escapeLatex(issue.recommendation())));
                }
                sb.append("\n");
            }
            sb.append("\\end{description}\n\n");
        }

        return sb.toString();
    }

    /**
     * Section 6: Traceability Analysis — requirement-based table mapping
     * Requirement → UC → Design → Test. UCs without a parent requirement
     * are listed separately at the bottom.
     */
    private String buildTraceabilitySection(AuditReport report) {
        var sb = new StringBuilder();

        if (report.traceabilityMatrix() == null || report.traceabilityMatrix().isEmpty()) {
            sb.append("No traceability data was extracted from the document.\n\n");
            return sb.toString();
        }

        List<TraceabilityEntry> entries = report.traceabilityMatrix();

        sb.append("This section maps each requirement to its use cases, design references, ");
        sb.append("and test coverage.\n\n");

        // Group by requirementId (preserve order)
        Map<String, List<TraceabilityEntry>> byReq = new LinkedHashMap<>();
        List<TraceabilityEntry> orphanUCs = new ArrayList<>();

        for (TraceabilityEntry e : entries) {
            if (e.requirementId() != null && !e.requirementId().isBlank()) {
                byReq.computeIfAbsent(e.requirementId(), k -> new ArrayList<>()).add(e);
            } else {
                orphanUCs.add(e);
            }
        }

        // Build table
        sb.append("\\begin{center}\n");
        sb.append("\\begin{longtable}{l l c c}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Requirement} & \\textbf{UC} & \\textbf{Design} & \\textbf{Test} \\\\\n");
        sb.append("\\midrule\n");
        sb.append("\\endhead\n\n");

        // Entries grouped by requirement
        for (var entry : byReq.entrySet()) {
            String reqId = entry.getKey();
            List<TraceabilityEntry> reqEntries = entry.getValue();

            boolean first = true;
            for (TraceabilityEntry e : reqEntries) {
                String reqCol = first ? "\\hyperlink{req:%s}{%s}".formatted(reqId, escapeLatex(reqId)) : "";
                first = false;

                if (e.useCaseId() != null && !e.useCaseId().isBlank()) {
                    sb.append("%s & \\hyperlink{uc:%s}{%s} & %s & %s \\\\\n".formatted(
                            reqCol,
                            e.useCaseId(), e.useCaseId(),
                            e.hasDesign() ? "\\ok" : "\\nok",
                            e.hasTest() ? "\\ok" : "\\nok"));
                } else {
                    // Requirement with NO UC
                    sb.append("%s & --- & --- & --- \\\\\n".formatted(reqCol));
                }
            }
            sb.append("\\midrule\n");
        }

        // Orphan UCs (no parent requirement)
        if (!orphanUCs.isEmpty()) {
            sb.append("\\midrule\n");
            sb.append("\\multicolumn{4}{l}{\\textit{Use cases without a parent requirement:}} \\\\\n");
            sb.append("\\midrule\n");

            for (TraceabilityEntry e : orphanUCs) {
                if (e.useCaseId() != null && !e.useCaseId().isBlank()) {
                    String ucName = e.useCaseName() != null && !e.useCaseName().isBlank()
                            ? " (%s)".formatted(escapeLatex(e.useCaseName())) : "";
                    sb.append("--- & \\hyperlink{uc:%s}{%s}%s & %s & %s \\\\\n".formatted(
                            e.useCaseId(), e.useCaseId(), ucName,
                            e.hasDesign() ? "\\ok" : "\\nok",
                            e.hasTest() ? "\\ok" : "\\nok"));
                }
            }
        }

        sb.append("\\bottomrule\n");
        sb.append("\\end{longtable}\n");
        sb.append("\\end{center}\n\n");

        // Summary statistics
        long totalReqs = byReq.size();
        long reqsWithUC = byReq.entrySet().stream()
                .filter(e -> e.getValue().stream()
                        .anyMatch(t -> t.useCaseId() != null && !t.useCaseId().isBlank()))
                .count();
        long totalUCEntries = entries.stream()
                .filter(e -> e.useCaseId() != null && !e.useCaseId().isBlank())
                .count();
        long fullyCovered = entries.stream()
                .filter(e -> e.useCaseId() != null && !e.useCaseId().isBlank()
                        && e.hasDesign() && e.hasTest())
                .count();
        long missingTest = entries.stream()
                .filter(e -> e.useCaseId() != null && !e.useCaseId().isBlank() && !e.hasTest())
                .count();

        sb.append("\\textbf{Summary:} Of %d requirements, %d are linked to UCs, %d have no UC. "
                .formatted(totalReqs, reqsWithUC, totalReqs - reqsWithUC));
        sb.append("Of %d UCs, %d have full coverage (design + test), %d lack test coverage"
                .formatted(totalUCEntries, fullyCovered, missingTest));
        if (!orphanUCs.isEmpty()) {
            sb.append(", %d have no parent requirement".formatted(orphanUCs.size()));
        }
        sb.append(".\n\n");

        // Suggestion
        sb.append("\\paragraph{Suggestion:} Prioritize adding tests for core operations, ");
        sb.append("then address any requirements without associated use cases.\n\n");

        return sb.toString();
    }

    /**
     * Generates a brief overview of the testing strategy from the document.
     */
    private String generateTestingStrategyOverview(String fullText) {
        String systemPrompt = """
            You are a Software Engineering expert. Analyze the document and describe
            the TESTING STRATEGY used by the student.
            Generate the output directly as valid LaTeX code. Write in ENGLISH.
            
            Describe:
            - What types of tests are present (unit, integration, system, acceptance)?
            - What testing frameworks/tools are used?
            - What is the coverage approach?
            - Are boundary conditions and error cases tested?
            
            OUTPUT FORMAT (pure LaTeX):
            - Use normal paragraphs and \\begin{itemize} for lists
            - Maximum 200 words
            - Escape correctly: & as \\&, % as \\%, _ as \\_
            - DO NOT generate \\documentclass, \\begin{document}, \\section
            - DO NOT use Unicode characters
            - If the document does not describe testing, state: "The document does not
              describe a testing strategy."
            - Use a formal, professional, and objective tone
            """;

        try {
            return callLlmWithFallback(systemPrompt,
                    "Describe the testing strategy in this document:\n\n" + truncateForPrompt(fullText, 30000));
        } catch (Exception e) {
            log.warn("Unable to generate testing strategy overview: {}", e.getMessage());
            return "The testing strategy overview is not available due to an error in automatic generation.";
        }
    }

    /**
     * Generates a brief architecture overview from the document via LLM.
     */
    private String generateArchitectureOverview(String fullText) {
        String systemPrompt = """
            You are a Software Engineering expert. Analyze the document and describe
            the ARCHITECTURE used by the student's project.
            Generate the output directly as valid LaTeX code. Write in ENGLISH.
            
            Describe:
            - What architectural pattern is used (layered, MVC, microservices, etc.)?
            - What are the main components/modules?
            - What technologies/frameworks are used?
            - How are the components connected?
            
            OUTPUT FORMAT (pure LaTeX):
            - Use normal paragraphs and \\textbf{} for emphasis
            - Maximum 150 words
            - Escape correctly: & as \\&, % as \\%, _ as \\_
            - DO NOT generate \\documentclass, \\begin{document}, \\section
            - DO NOT use Unicode characters
            - If the document does not describe an architecture, state: "The document does not
              describe a system architecture."
            - Use a formal, professional, and objective tone
            """;

        try {
            return callLlmWithFallback(systemPrompt,
                    "Describe the architecture in this document:\n\n" + truncateForPrompt(fullText, 30000));
        } catch (Exception e) {
            log.warn("Unable to generate architecture overview: {}", e.getMessage());
            return "The architecture overview is not available due to an error in automatic generation.";
        }
    }

    /**
     * Generates a concrete suggestion for improving traceability of a specific UC.
     */
    private String buildTraceabilitySuggestion(TraceabilityEntry entry) {
        if (!entry.hasDesign() && !entry.hasTest()) {
            return "Add both a design reference (e.g., class diagram, sequence diagram, or component description) "
                    + "and at least one test case that verifies this use case.";
        } else if (!entry.hasDesign()) {
            return "Add a design reference that maps this use case to its implementing components "
                    + "(e.g., controller, service, DAO classes or a sequence diagram).";
        } else if (!entry.hasTest()) {
            return "Add at least one test case (unit or integration) that explicitly verifies "
                    + "the behavior described in this use case.";
        }
        return "";
    }

    /**
     * Section 3: Architecture Analysis — LLM description + architecture issues.
     */
    private String buildArchitectureSection(AuditReport report, String fullText) {
        var sb = new StringBuilder();

        // Generate architecture description via LLM
        String archOverview = generateArchitectureOverview(fullText);
        sb.append(sanitizeLlmLatex(archOverview));
        sb.append("\n\n");

        // Architecture issues
        List<AuditIssue> archIssues = report.issues().stream()
                .filter(i -> "Architecture".equalsIgnoreCase(i.category()))
                .toList();

        if (archIssues.isEmpty()) {
            sb.append("No significant architecture issues were identified.\n\n");
        } else {
            sb.append("\\begin{description}[style=nextline, leftmargin=1em]\n");
            for (AuditIssue issue : archIssues) {
                String sevBadge = severityBadge(issue.severity());
                sb.append("  \\item[%s --- %s]\n".formatted(
                        escapeLatex(issue.id()), sevBadge));

                sb.append("  \\textbf{Problem:} %s\n".formatted(
                        escapeLatex(issue.shortDescription() != null ? issue.shortDescription() : issue.description())));

                if (issue.recommendation() != null && !issue.recommendation().isBlank()) {
                    sb.append("\n  \\textbf{Suggestion:} %s\n".formatted(
                            escapeLatex(issue.recommendation())));
                }
                sb.append("\n");
            }
            sb.append("\\end{description}\n\n");
        }

        return sb.toString();
    }

    /**
     * Returns a text-based severity badge (no colors, accessible).
     */
    private String severityBadge(Severity severity) {
        return switch (severity) {
            case HIGH -> "\\fbox{\\textsc{high}}";
            case MEDIUM -> "\\fbox{\\textsc{medium}}";
            case LOW -> "\\fbox{\\textsc{low}}";
        };
    }

    // ═══════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════

    private String findUcName(AuditReport report, String ucId) {
        if (report.traceabilityMatrix() == null) return "";
        return report.traceabilityMatrix().stream()
                .filter(e -> e.useCaseId().equalsIgnoreCase(ucId))
                .map(TraceabilityEntry::useCaseName)
                .findFirst().orElse("");
    }

    /**
     * Extracts all UC references from an issue's text fields.
     */
    private Set<String> extractAllUCs(AuditIssue issue) {
        String combined = (issue.description() != null ? issue.description() : "") + " "
                + (issue.quote() != null ? issue.quote() : "") + " "
                + (issue.recommendation() != null ? issue.recommendation() : "");

        Set<String> ucs = new LinkedHashSet<>();
        var matcher = java.util.regex.Pattern.compile("(?i)UC[- ]?(\\d+)")
                .matcher(combined);
        while (matcher.find()) {
            ucs.add("UC-" + matcher.group(1));
        }
        return ucs;
    }

    /**
     * Truncates a verbatim quote to a maximum length, cutting at a sentence boundary if possible.
     */
    private String truncateQuote(String quote, int maxLen) {
        if (quote == null || quote.length() <= maxLen) return quote;

        // Try to cut at a sentence boundary
        String prefix = quote.substring(0, maxLen);
        int cutPoint = prefix.lastIndexOf(". ");
        if (cutPoint > maxLen / 2) {
            return quote.substring(0, cutPoint + 1) + " [...]";
        }

        // Otherwise cut at word boundary
        int spaceIdx = prefix.lastIndexOf(' ');
        if (spaceIdx > maxLen / 2) {
            return quote.substring(0, spaceIdx) + " [...]";
        }

        return quote.substring(0, maxLen) + " [...]";
    }

    // ═══════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════

    private String sanitizeLlmLatex(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return "";
        String cleaned = llmOutput;
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\documentclass.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\usepackage.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\begin\\{document\\}.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\end\\{document\\}.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\maketitle.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\tableofcontents.*$", "");
        cleaned = cleaned.replaceAll("\\\\input\\{[^}]*\\}", "");
        cleaned = cleaned.replaceAll("\\\\include\\{[^}]*\\}", "");
        cleaned = cleaned.replaceAll("(?m)^```(?:latex|tex)?\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^```\\s*$", "");
        return cleaned.strip();
    }

    private String truncateForPrompt(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        log.warn("Text truncated from {} to {} characters for the prompt", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... text truncated ...]";
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    /**
     * Escapes LaTeX special characters for text INSIDE commands.
     * Collapses newlines into spaces.
     */
    private String escapeLatex(String text) {
        if (text == null) return "";
        String sanitized = text.replaceAll("\\r\\n", "\n")
                               .replaceAll("\\n{2,}", " ")
                               .replaceAll("\\n", " ");
        return escapeSpecialChars(sanitized);
    }

    private String escapeSpecialChars(String text) {
        var sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\textbackslash{}");
                case '&' -> sb.append("\\&");
                case '%' -> sb.append("\\%");
                case '$' -> sb.append("\\$");
                case '#' -> sb.append("\\#");
                case '_' -> sb.append("\\_");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '~' -> sb.append("\\textasciitilde{}");
                case '^' -> sb.append("\\textasciicircum{}");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
