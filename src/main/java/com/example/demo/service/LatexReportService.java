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
 * 1. Document Context
 * 2. Executive Summary
 * 3. Strengths
 * 4. Feature Coverage (from MongoDB)
 * 5. Summary Table
 * 6. Category Detail (grouped by UC)
 * 7. Priority Recommendations
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

    public Path generateReport(AuditReport report, String fullText) {
        log.info("Generating LaTeX report for '{}' ({} issues, {} features)",
                report.documentName(), report.totalIssues(), report.featureCoverage().size());

        String documentContext = generateDocumentContext(fullText);
        String executiveSummary = generateExecutiveSummary(report);
        String strengths = generateStrengths(report, fullText);
        String latexContent = buildLatexDocument(report, executiveSummary, documentContext, strengths);

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

    private String generateDocumentContext(String fullText) {
        String contextPrompt = """
            Analyze the following text from a software project document (thesis/report)
            and generate a structured overview directly in valid LaTeX code.
            
            You must extract and summarize ALL the following sections. DO NOT truncate the output.
            Generate ONLY the LaTeX body (NO \\documentclass, NO \\begin{document}).
            
            OUTPUT FORMAT (pure LaTeX):
            Use \\subsection*{Project Objective} for each heading.
            Use \\begin{itemize} ... \\item ... \\end{itemize} for lists.
            Use \\texttt{} for technical names and \\textbf{} for emphasis.
            
            SECTIONS TO GENERATE:
            - Project Objective: what the application does in 2-3 sentences.
            - Main Use Cases: list ALL Use Cases in the format "UC-N -- Name: short description".
            - Functional Requirements: list the key functional requirements.
            - Non-Functional Requirements: list the non-functional requirements (performance, security, etc.).
            - Architecture: briefly describe the architecture (patterns, technologies, structure).
            - Testing Strategy: briefly describe how the requirements are tested.
            
            CRITICAL RULES:
            - Write in English
            - Be CONCISE: max 2 lines per point, max 1 line per requirement/UC
            - Generate ONLY valid and compilable LaTeX code
            - Properly escape special characters: & as \\&, % as \\%, _ as \\_
            - DO NOT generate \\documentclass, \\begin{document}, \\end{document}
            - DO NOT use non-standard packages (only itemize, enumerate, subsection, textbf, texttt)
            - DO NOT use Unicode characters or special symbols: use only ASCII characters and standard LaTeX commands (e.g., $\\in$ instead of ∈)
            - If a section is not present in the document, write "Not described in the document."
            - COMPLETE ALL SECTIONS: do not stop halfway
            """;

        try {
                String context = callLlmWithFallback(contextPrompt,
                    "Ecco il testo del documento:\n\n" + fullText);
            log.debug("Contesto documento generato ({} caratteri)", context.length());
            return context;
        } catch (Exception e) {
            log.warn("Impossibile generare contesto documento: {}", e.getMessage());
            return "Il contesto del documento non e' disponibile a causa di un errore nella generazione automatica.";
        }
    }

    private String generateExecutiveSummary(AuditReport report) {
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
                        i.severity(), i.id(), i.description(), i.pageReference()));
            }
        }

        String systemPrompt = """
            You are a Software Engineering expert. Generate an executive summary in English
            for an audit report of a SWE document written by a university student.
            Generate the output directly in valid LaTeX code.
                
            It must be concise (3-5 short paragraphs) and must:
            1. Briefly frame the document and its purpose (2-3 sentences)
            2. Highlight the patterns of issues found (do not list every single issue)
            3. Indicate the critical areas (severity HIGH)
            4. Give an overall quality assessment
            5. Suggest the 3 priority actions
                
            OUTPUT FORMAT (pure LaTeX):
            - Use normal paragraphs separated by blank lines
            - Use \\textbf{} for emphasis and \\begin{enumerate} for priority actions
            - Properly escape: & as \\&, % as \\%, _ as \\_
            - DO NOT generate \\documentclass, \\begin{document}, \\section
            - DO NOT use non-standard packages
            - DO NOT use Unicode characters or special symbols: use only ASCII characters and standard LaTeX commands (e.g., $\\in$ instead of ∈)
            - COMPLETE the entire summary: do not stop halfway
            - Maximum 500 words total
            """;

        String userPrompt = """
            Generate the executive summary for the audit of document '%s'.
            Total issues found: %d (HIGH: %d, MEDIUM: %d, LOW: %d)
                
            %s""".formatted(
            report.documentName(),
            report.totalIssues(),
            report.severityDistribution().getOrDefault(Severity.HIGH, 0L),
            report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L),
            report.severityDistribution().getOrDefault(Severity.LOW, 0L),
            issuesSummary);

        try {
            String summary = callLlmWithFallback(systemPrompt, userPrompt);
            log.debug("Executive summary generated ({} characters)", summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("Unable to generate executive summary: {}", e.getMessage());
            return ("The analysis of document '%s' found %d issues, " +
                    "of which %d are high severity. A thorough review of the critical areas identified in the report is recommended.").formatted(
                    report.documentName(),
                    report.totalIssues(),
                    report.severityDistribution().getOrDefault(Severity.HIGH, 0L));
        }
    }

    /**
     * Generates the "Strengths" section — positive aspects of the document.
     */
    private String generateStrengths(AuditReport report, String fullText) {
        String systemPrompt = """
                Sei un esperto di Ingegneria del Software. Analizza un documento di progetto SWE
                e identifica i PUNTI DI FORZA del lavoro dello studente.
                
                Genera l'output direttamente in codice LaTeX valido. Scrivi in ITALIANO.
                
                Identifica 3-6 aspetti positivi del documento, ad esempio:
                - Struttura chiara e ben organizzata
                - Buona copertura dei casi d'uso
                - Testing approfondito
                - Documentazione dei requisiti completa
                - Architettura ben definita
                - Uso corretto di pattern di design
                
                FORMATO OUTPUT (LaTeX puro):
                Genera una \\begin{itemize} con un \\item per ogni punto di forza.
                Usa \\textbf{} per il titolo del punto e testo normale per la spiegazione.
                Escappa correttamente: & come \\&, % come \\%, _ come \\_
                NON generare \\documentclass, \\begin{document}, \\section.
                
                REGOLE:
                - Ogni punto di forza deve essere specifico e basato su EVIDENZE nel testo
                - NON inventare punti di forza se non ci sono
                - Massimo 200 parole totali
                """;

        String userPrompt = """
                Identifica i punti di forza di questo documento SWE.
                Il documento ha %d problemi identificati nelle categorie: %s.
                
                DOCUMENTO (estratto):
                %s""".formatted(
                report.totalIssues(),
                report.issues().stream()
                        .map(i -> i.category() != null ? i.category() : "Altro")
                        .distinct().collect(Collectors.joining(", ")),
                        fullText);

        try {
            return callLlmWithFallback(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("Impossibile generare punti di forza: {}", e.getMessage());
            return "L'analisi dei punti di forza non e' disponibile.";
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
            log.warn("Anthropic ha restituito risposta vuota, provo con OpenAI...");
        } catch (Exception e) {
            log.warn("Anthropic fallito ({}), provo con OpenAI...", e.getMessage());
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

    /**
     * Accumulates token usage from a ChatResponse into the per-request
     * {@link TokenUsageAccumulator} (if one is active for the current thread).
     *
     * @param chatResponse the response to extract usage from
     * @param isAnthropic  {@code true} → counts toward Anthropic, {@code false} → OpenAI
     */
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
                log.debug("LatexReportService: +{} in / +{} out Anthropic tokens (model={})",
                        input, output, metadata.getModel());
            } else {
                acc.addOpenAiTokens(input, output);
                log.debug("LatexReportService: +{} in / +{} out OpenAI tokens (fallback, model={})",
                        input, output, metadata.getModel());
            }
        } catch (Exception e) {
            log.debug("LatexReportService: failed to capture token usage — {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // LaTeX document building
    // ═══════════════════════════════════════════════════

    private String buildLatexDocument(AuditReport report, String summary,
                                      String documentContext, String strengths) {
        var sb = new StringBuilder();

        // Preambolo
        sb.append("""
            \\documentclass[11pt,a4paper]{scrartcl}
            \\usepackage[utf8]{inputenc}
            \\usepackage[T1]{fontenc}
            \\usepackage[italian]{babel}
            \\usepackage{xcolor}
            \\usepackage{hyperref}
            \\usepackage{enumitem}
            \\usepackage{booktabs}
            \\usepackage{geometry}
            \\usepackage{fancyhdr}
            \\usepackage{longtable}
            \\usepackage{array}
            \\usepackage{amssymb}
            \\geometry{a4paper, margin=2.5cm}
                
            \\definecolor{highcolor}{RGB}{180,0,0}
            \\definecolor{medcolor}{RGB}{180,100,0}
            \\definecolor{lowcolor}{RGB}{80,80,80}
            \\definecolor{present}{RGB}{0,120,0}
            \\definecolor{partial}{RGB}{180,140,0}
            \\definecolor{absent}{RGB}{180,0,0}
            \\definecolor{scoreA}{RGB}{0,130,0}
            \\definecolor{scoreB}{RGB}{80,160,0}
            \\definecolor{scoreC}{RGB}{180,140,0}
            \\definecolor{scoreD}{RGB}{200,80,0}
            \\definecolor{scoreF}{RGB}{180,0,0}
                
            \\pagestyle{fancy}
            \\fancyhf{}
            \\fancyhead[L]{\\small SWE-Audit-Agent}
            \\fancyhead[R]{\\small \\today}
            \\fancyfoot[C]{\\thepage}
                
            \\title{Report di Audit Ingegneristico\\\\[0.3em]\\large %s}
            \\author{SWE-Audit-Agent}
            \\date{\\today}
            \\begin{document}
            \\maketitle
            \\tableofcontents
            \\newpage
            """.formatted(escapeLatex(report.documentName())));

        // ── 1. Contesto del Documento ──
        sb.append("\\section{Contesto del Documento}\n");
        sb.append("\\begin{small}\n");
        sb.append(sanitizeLlmLatex(documentContext));
        sb.append("\n\\end{small}\n\n");

        // ── 2. Sintesi Esecutiva ──
        sb.append("\\section{Sintesi Esecutiva}\n");
        sb.append(formatSynthesisSection(summary, report));
        sb.append("\n\n");

        // ── 3. Punti di Forza ──
        sb.append("\\section{Punti di Forza}\n");
        sb.append("\\begin{small}\n");
        sb.append(sanitizeLlmLatex(strengths));
        sb.append("\n\\end{small}\n\n");

        // ── 5. Copertura Feature (da MongoDB) ──
        if (!report.featureCoverage().isEmpty()) {
            sb.append("\\section{Copertura Feature Attese}\n");
            sb.append(buildFeatureCoverageSection(report.featureCoverage()));
        }

        // ── 6. Tabella Riassuntiva ──
        sb.append("\\section{Tabella Riassuntiva}\n");
        sb.append(buildSummaryTable(report));
        sb.append("\n");

        // ── 7. Dettaglio per Categoria (raggruppato per UC) ──
        sb.append("\\section{Dettaglio delle Problematiche}\n");
        if (report.issues().isEmpty()) {
            sb.append("Nessun problema rilevato durante l'audit.\n\n");
        } else {
            buildDetailByCategoryAndUC(sb, report);
        }

        // ── 8. Raccomandazioni Prioritarie ──
        sb.append("\\section{Raccomandazioni Prioritarie}\n");
        sb.append(buildPriorityRecommendations(report));

        // ── 9. Matrice di Tracciabilità ──
        if (report.traceabilityMatrix() != null && !report.traceabilityMatrix().isEmpty()) {
            sb.append("\\section{Matrice di Tracciabilit\\`a}\n");
            sb.append(buildTraceabilityMatrix(report.traceabilityMatrix()));
        }

        // ── 10. Coerenza Terminologica ──
        if (report.glossaryIssues() != null && !report.glossaryIssues().isEmpty()) {
            sb.append("\\section{Coerenza Terminologica}\n");
            sb.append(buildGlossaryIssuesSection(report.glossaryIssues()));
        }

        sb.append("\\end{document}\n");
        return sb.toString();
    }

    /**
     * Scorecard -- computes a grade (A-F) for each category based on issue count and severity.
     * Formula: score = max(0, 100 - HIGH*25 - MEDIUM*10 - LOW*3)
     * A: 90-100, B: 75-89, C: 60-74, D: 40-59, F: 0-39
     * NOTE: currently unused, retained for future use.
     */
    @SuppressWarnings("unused")
    private String buildScorecard(AuditReport report) {
        if (report.issues().isEmpty()) {
            return "Nessun problema rilevato: valutazione complessiva eccellente.\n\n";
        }

        Map<String, List<AuditIssue>> byCategory = report.issues().stream()
                .collect(Collectors.groupingBy(
                        i -> i.category() != null ? i.category() : "Altro",
                        LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();
        sb.append("\\begin{center}\n");
        sb.append("\\begin{tabular}{l c c c c c}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Area} & \\textbf{HIGH} & \\textbf{MEDIUM} & \\textbf{LOW} & \\textbf{Punteggio} & \\textbf{Voto} \\\\\n");
        sb.append("\\midrule\n");

        int totalScore = 0;
        int categoryCount = 0;

        for (var entry : byCategory.entrySet()) {
            long high = entry.getValue().stream().filter(i -> i.severity() == Severity.HIGH).count();
            long med = entry.getValue().stream().filter(i -> i.severity() == Severity.MEDIUM).count();
            long low = entry.getValue().stream().filter(i -> i.severity() == Severity.LOW).count();

            int score = (int) Math.max(0, 100 - high * 25 - med * 10 - low * 3);
            String grade = scoreToGrade(score);
            String gradeColor = scoreToColor(score);
            totalScore += score;
            categoryCount++;

            sb.append("%s & %d & %d & %d & %d/100 & \\textcolor{%s}{\\textbf{%s}} \\\\\n".formatted(
                    escapeLatex(entry.getKey()), high, med, low, score, gradeColor, grade));
        }

        int avgScore = categoryCount > 0 ? totalScore / categoryCount : 100;
        String avgGrade = scoreToGrade(avgScore);
        String avgColor = scoreToColor(avgScore);

        sb.append("\\midrule\n");
        sb.append("\\textbf{Media Complessiva} & & & & \\textbf{%d/100} & \\textcolor{%s}{\\textbf{%s}} \\\\\n"
                .formatted(avgScore, avgColor, avgGrade));
        sb.append("\\bottomrule\n");
        sb.append("\\end{tabular}\n");
        sb.append("\\end{center}\n\n");

        return sb.toString();
    }

    private String scoreToGrade(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 60) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private String scoreToColor(int score) {
        if (score >= 90) return "scoreA";
        if (score >= 75) return "scoreB";
        if (score >= 60) return "scoreC";
        if (score >= 40) return "scoreD";
        return "scoreF";
    }

    /**
     * Feature coverage section — table with each feature's status and coverage bar.
     */
    private String buildFeatureCoverageSection(List<FeatureCoverage> features) {
        var sb = new StringBuilder();

        // Summary count
        long present = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
        long partial = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
        long absent = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();
        int avgCoverage = (int) features.stream().mapToInt(FeatureCoverage::coverageScore).average().orElse(0);

        sb.append("Su %d feature attese: \\textcolor{present}{%d presenti}, \\textcolor{partial}{%d parziali}, \\textcolor{absent}{%d assenti}. "
                .formatted(features.size(), present, partial, absent));
        sb.append("Copertura media: \\textbf{%d\\%%}.\n\n".formatted(avgCoverage));

        // Detailed table
        sb.append("\\begin{longtable}{p{5cm} c c p{6cm}}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Feature} & \\textbf{Stato} & \\textbf{Copertura} & \\textbf{Evidenza} \\\\\n");
        sb.append("\\midrule\n");
        sb.append("\\endhead\n");

        for (FeatureCoverage f : features) {
            String statusLabel = switch (f.status()) {
                case PRESENT -> "\\textcolor{present}{Presente}";
                case PARTIAL -> "\\textcolor{partial}{Parziale}";
                case ABSENT -> "\\textcolor{absent}{Assente}";
            };

            String coverageLabel = "%d\\%% (%d/%d)".formatted(f.coverageScore(), f.matchedItems(), f.totalItems());

            sb.append("%s & %s & %s & {\\small %s} \\\\\n".formatted(
                    escapeLatex(f.featureName()),
                    statusLabel,
                    coverageLabel,
                    escapeLatex(f.evidence() != null ? f.evidence() : "")));
        }

        sb.append("\\bottomrule\n");
        sb.append("\\end{longtable}\n\n");

        return sb.toString();
    }

    /**
     * Summary table Category x Severity.
     */
    private String buildSummaryTable(AuditReport report) {
        if (report.issues().isEmpty()) {
            return "Nessun problema rilevato.\n";
        }

        Map<String, Map<Severity, Long>> matrix = report.issues().stream()
                .collect(Collectors.groupingBy(
                        i -> i.category() != null ? i.category() : "Altro",
                        LinkedHashMap::new,
                        Collectors.groupingBy(AuditIssue::severity, Collectors.counting())));

        var sb = new StringBuilder();
        sb.append("\\begin{center}\n");
        sb.append("\\begin{tabular}{l c c c c}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Categoria} & \\textcolor{highcolor}{\\textbf{HIGH}} & " +
                "\\textcolor{medcolor}{\\textbf{MEDIUM}} & \\textcolor{lowcolor}{\\textbf{LOW}} & " +
                "\\textbf{Totale} \\\\\n");
        sb.append("\\midrule\n");

        for (var entry : matrix.entrySet()) {
            long high = entry.getValue().getOrDefault(Severity.HIGH, 0L);
            long med = entry.getValue().getOrDefault(Severity.MEDIUM, 0L);
            long low = entry.getValue().getOrDefault(Severity.LOW, 0L);
            long total = high + med + low;
            sb.append("%s & %d & %d & %d & %d \\\\\n".formatted(
                    escapeLatex(entry.getKey()), high, med, low, total));
        }

        sb.append("\\midrule\n");
        sb.append("\\textbf{Totale} & \\textbf{%d} & \\textbf{%d} & \\textbf{%d} & \\textbf{%d} \\\\\n".formatted(
                report.severityDistribution().getOrDefault(Severity.HIGH, 0L),
                report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L),
                report.severityDistribution().getOrDefault(Severity.LOW, 0L),
                report.totalIssues()));
        sb.append("\\bottomrule\n");
        sb.append("\\end{tabular}\n");
        sb.append("\\end{center}\n");

        return sb.toString();
    }

    /**
     * Category detail with Use Case grouping where detectable.
     * Issues mentioning "UC-N" are grouped under that use case.
     */
    private void buildDetailByCategoryAndUC(StringBuilder sb, AuditReport report) {
        Map<String, List<AuditIssue>> byCategory = report.issues().stream()
                .collect(Collectors.groupingBy(
                        i -> i.category() != null ? i.category() : "Altro",
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Build cross-reference map: issue ID → list of related IDs in same UC
        Map<String, List<String>> crossRefs = buildCrossReferences(report.issues());

        for (var entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<AuditIssue> categoryIssues = entry.getValue();

            sb.append("\\subsection{%s (%d problemi)}\n".formatted(
                    escapeLatex(category), categoryIssues.size()));

            // Group by UC within category
            Map<String, List<AuditIssue>> byUC = new LinkedHashMap<>();
            List<AuditIssue> noUC = new ArrayList<>();

            for (AuditIssue issue : categoryIssues) {
                String uc = extractUC(issue);
                if (uc != null) {
                    byUC.computeIfAbsent(uc, k -> new ArrayList<>()).add(issue);
                } else {
                    noUC.add(issue);
                }
            }

            // Render UC groups
            for (var ucEntry : byUC.entrySet()) {
                sb.append("\\subsubsection*{%s}\n".formatted(escapeLatex(ucEntry.getKey())));
                for (AuditIssue issue : ucEntry.getValue()) {
                    sb.append(buildIssueBlock(issue, crossRefs));
                }
            }

            // Render ungrouped
            if (!noUC.isEmpty()) {
                if (!byUC.isEmpty()) {
                    sb.append("\\subsubsection*{Problemi Generali}\n");
                }
                for (AuditIssue issue : noUC) {
                    sb.append(buildIssueBlock(issue, crossRefs));
                }
            }
        }
    }

    /**
     * Extracts the UC reference from an issue (searching for "UC-N" or "UC N" in description/quote).
     */
    private String extractUC(AuditIssue issue) {
        String combined = (issue.description() != null ? issue.description() : "") + " "
                + (issue.quote() != null ? issue.quote() : "") + " "
                + (issue.recommendation() != null ? issue.recommendation() : "");

        // Pattern: UC-1, UC-2, UC 1, UC1, etc.
        var matcher = java.util.regex.Pattern.compile("(?i)UC[- ]?(\\d+)")
                .matcher(combined);
        if (matcher.find()) {
            return "UC-" + matcher.group(1);
        }
        return null;
    }

    /**
     * Builds a cross-reference map: for each issue, the related issues (same UC).
     */
    private Map<String, List<String>> buildCrossReferences(List<AuditIssue> issues) {
        // Map UC → issue IDs
        Map<String, List<String>> ucToIds = new LinkedHashMap<>();
        Map<String, String> issueToUC = new HashMap<>();

        for (AuditIssue issue : issues) {
            String uc = extractUC(issue);
            if (uc != null) {
                ucToIds.computeIfAbsent(uc, k -> new ArrayList<>()).add(issue.id());
                issueToUC.put(issue.id(), uc);
            }
        }

        // Now for each issue, find other issues with same UC but different ID prefix
        Map<String, List<String>> crossRefs = new HashMap<>();
        for (AuditIssue issue : issues) {
            String uc = issueToUC.get(issue.id());
            if (uc == null) continue;

            String myPrefix = issue.id().contains("-") ? issue.id().substring(0, issue.id().indexOf('-')) : "";
            List<String> related = ucToIds.get(uc).stream()
                    .filter(id -> !id.equals(issue.id()))
                    .filter(id -> {
                        String p = id.contains("-") ? id.substring(0, id.indexOf('-')) : "";
                        return !p.equals(myPrefix); // cross-category references only
                    })
                    .toList();

            if (!related.isEmpty()) {
                crossRefs.put(issue.id(), related);
            }
        }

        return crossRefs;
    }

    /**
     * LaTeX block for a single issue, with cross-reference, recommendation, and confidence badge.
     */
    private String buildIssueBlock(AuditIssue issue, Map<String, List<String>> crossRefs) {
        String severityLabel = switch (issue.severity()) {
            case HIGH -> "\\textcolor{highcolor}{HIGH}";
            case MEDIUM -> "\\textcolor{medcolor}{MEDIUM}";
            case LOW -> "\\textcolor{lowcolor}{LOW}";
        };

        // Confidence badge
        int confPct = (int) Math.round(issue.confidenceScore() * 100);
        String confColor = confPct >= 80 ? "present" : confPct >= 60 ? "partial" : "absent";
        String confBadge = "\\textcolor{%s}{[%d\\%%]}".formatted(confColor, confPct);

        var sb = new StringBuilder();
        sb.append("\\paragraph{%s \\textnormal{--- %s %s --- Pagina %d}}\n".formatted(
                escapeLatex(issue.id()), severityLabel, confBadge, issue.pageReference()));

        sb.append("%s\n\n".formatted(escapeLatex(issue.description())));

        sb.append("\\begin{quote}\n");
        sb.append("\\small\\itshape %s\n".formatted(escapeLatex(issue.quote())));
        sb.append("\\end{quote}\n\n");

        if (issue.recommendation() != null && !issue.recommendation().isBlank()) {
            sb.append("\\textbf{Raccomandazione:} %s\n\n".formatted(
                    escapeLatex(issue.recommendation())));
        }

        // Cross-references
        List<String> refs = crossRefs.get(issue.id());
        if (refs != null && !refs.isEmpty()) {
            sb.append("{\\small\\textit{Vedi anche: %s}}\n\n".formatted(
                    refs.stream().map(this::escapeLatex).collect(Collectors.joining(", "))));
        }

        return sb.toString();
    }

    /**
     * Priority recommendations — HIGH only, one line each with ID and action.
     */
    private String buildPriorityRecommendations(AuditReport report) {
        List<AuditIssue> highIssues = report.issues().stream()
                .filter(i -> i.severity() == Severity.HIGH)
                .toList();

        if (highIssues.isEmpty()) {
            return "Non sono stati rilevati problemi ad alta severita. " +
                    "Si consiglia comunque di revisionare le segnalazioni di severita MEDIUM.\n\n";
        }

        var sb = new StringBuilder();
        sb.append("Le seguenti azioni sono considerate prioritarie:\n\n");
        sb.append("\\begin{enumerate}\n");

        for (AuditIssue issue : highIssues) {
            // One-line concise recommendation with ID reference
            String rec = issue.recommendation() != null && !issue.recommendation().isBlank()
                    ? truncateRecommendation(issue.recommendation(), 150)
                    : truncateRecommendation(issue.description(), 150);
            sb.append("  \\item \\textbf{%s} (pag.~%d): %s\n".formatted(
                    escapeLatex(issue.id()), issue.pageReference(), escapeLatex(rec)));
        }

        sb.append("\\end{enumerate}\n\n");
        return sb.toString();
    }

    /**
     * Truncates a recommendation to maxLen characters, cutting at the nearest sentence end.
     */
    private String truncateRecommendation(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text != null ? text : "";
        // Try to cut at last period before maxLen
        int lastPeriod = text.lastIndexOf('.', maxLen);
        if (lastPeriod > maxLen / 2) {
            return text.substring(0, lastPeriod + 1);
        }
        return text.substring(0, maxLen) + "...";
    }

    // ═══════════════════════════════════════════════════
    // New section builders (Traceability, Glossary, Layout)
    // ═══════════════════════════════════════════════════

    /**
     * Sanitizes LLM-generated LaTeX output: removes dangerous commands
     * like \documentclass, \begin{document}, \end{document}, \input, \include.
     * Keeps all other LaTeX intact.
     */
    private String sanitizeLlmLatex(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return "";
        String cleaned = llmOutput;
        // Remove document-level commands that would break our template
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\documentclass.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\usepackage.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\begin\\{document\\}.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\end\\{document\\}.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\maketitle.*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\\\tableofcontents.*$", "");
        // Remove dangerous file inclusion commands
        cleaned = cleaned.replaceAll("\\\\input\\{[^}]*\\}", "");
        cleaned = cleaned.replaceAll("\\\\include\\{[^}]*\\}", "");
        // Remove markdown code fences that LLM might wrap output in
        cleaned = cleaned.replaceAll("(?m)^```(?:latex|tex)?\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^```\\s*$", "");
        return cleaned.strip();
    }

    /**
     * Formats the document context with mini-subsections for readability.
     * Recognizes headings in LLM text and converts them to \subsection*.
     * NOTE: currently unused (LLM now generates LaTeX directly), retained for future use.
     */
    @SuppressWarnings("unused")
    private String formatContextSection(String rawContext) {
        if (rawContext == null || rawContext.isBlank()) return "";

        var sb = new StringBuilder();
        String[] lines = rawContext.split("\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append("\n");
                continue;
            }
            // Detect section headers (all caps or ending with colon)
            if (isSectionHeader(trimmed)) {
                String headerText = trimmed.replaceAll(":$", "").trim();
                sb.append("\n\\subsection*{%s}\n".formatted(escapeLatex(headerText)));
            } else {
                sb.append(escapeLatexBlock(trimmed)).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean isSectionHeader(String line) {
        // Common headers from the LLM context prompt
        String upper = line.toUpperCase();
        return upper.startsWith("OBIETTIVO DEL PROGETTO") ||
               upper.startsWith("CASI D'USO") || upper.startsWith("CASI D USO") ||
               upper.startsWith("REQUISITI FUNZIONALI") ||
               upper.startsWith("REQUISITI NON FUNZIONALI") ||
               upper.startsWith("ARCHITETTURA") ||
               upper.startsWith("STRATEGIA DI TESTING") ||
               upper.startsWith("TECNOLOGIE") ||
               (line.endsWith(":") && line.length() < 60 && !line.contains(".") && !line.startsWith("-"));
    }

    /**
     * Formats the executive summary with an initial summary box with metrics,
     * followed by the LLM text.
     */
    private String formatSynthesisSection(String summary, AuditReport report) {
        var sb = new StringBuilder();

        // Mini-dashboard box at top
        long high = report.severityDistribution().getOrDefault(Severity.HIGH, 0L);
        long med = report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L);
        long low = report.severityDistribution().getOrDefault(Severity.LOW, 0L);

        sb.append("\\noindent\\fbox{\\parbox{\\dimexpr\\textwidth-2\\fboxsep-2\\fboxrule}{%\n");
        sb.append("\\centering\\textbf{Panoramica Rapida} \\\\[0.3em]\n");
        sb.append("Problemi totali: \\textbf{%d} \\quad --- \\quad ".formatted(report.totalIssues()));
        sb.append("\\textcolor{highcolor}{HIGH: \\textbf{%d}} \\quad ".formatted(high));
        sb.append("\\textcolor{medcolor}{MEDIUM: \\textbf{%d}} \\quad ".formatted(med));
        sb.append("\\textcolor{lowcolor}{LOW: \\textbf{%d}}\n".formatted(low));

        // Average confidence if available
        if (!report.issues().isEmpty()) {
            double avgConf = report.issues().stream()
                    .mapToDouble(AuditIssue::confidenceScore).average().orElse(0.0);
            sb.append("\\\\[0.2em] Confidence media: \\textbf{%d\\%%}\n".formatted(
                    (int) Math.round(avgConf * 100)));
        }
        sb.append("}}\n\\vspace{0.5em}\n\n");

        // LLM narrative text (LaTeX diretto)
        sb.append("\\begin{small}\n");
        sb.append(sanitizeLlmLatex(summary));
        sb.append("\n\\end{small}\n");

        return sb.toString();
    }

    /**
     * Traceability matrix UC → Design → Test, with ✓/✗ indicators and gaps.
     */
    private String buildTraceabilityMatrix(List<TraceabilityEntry> entries) {
        var sb = new StringBuilder();

        long fullyCovered = entries.stream().filter(e -> e.hasDesign() && e.hasTest()).count();
        long missingDesign = entries.stream().filter(e -> !e.hasDesign()).count();
        long missingTest = entries.stream().filter(e -> !e.hasTest()).count();

        sb.append("Su %d casi d'uso tracciati: \\textcolor{present}{%d completamente coperti}, ".formatted(
                entries.size(), fullyCovered));
        sb.append("\\textcolor{absent}{%d senza design, %d senza test}.\n\n".formatted(missingDesign, missingTest));

        sb.append("\\begin{longtable}{l l c c p{5.5cm}}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{ID} & \\textbf{Caso d'Uso} & \\textbf{Design} & \\textbf{Test} & \\textbf{Gap} \\\\\n");
        sb.append("\\midrule\n");
        sb.append("\\endhead\n");

        for (TraceabilityEntry e : entries) {
            String designMark = e.hasDesign()
                    ? "\\textcolor{present}{$\\checkmark$}"
                    : "\\textcolor{absent}{$\\times$}";
            String testMark = e.hasTest()
                    ? "\\textcolor{present}{$\\checkmark$}"
                    : "\\textcolor{absent}{$\\times$}";
            String gapText = e.gap() != null && !e.gap().isBlank()
                    ? escapeLatex(truncateRecommendation(e.gap(), 120))
                    : "---";

            sb.append("%s & %s & %s & %s & {\\small %s} \\\\\n".formatted(
                    escapeLatex(e.useCaseId()),
                    escapeLatex(truncateRecommendation(e.useCaseName(), 35)),
                    designMark,
                    testMark,
                    gapText));
        }

        sb.append("\\bottomrule\n");
        sb.append("\\end{longtable}\n\n");

        return sb.toString();
    }

    /**
     * Terminological consistency section — glossary errors / inconsistent terminology.
     */
    private String buildGlossaryIssuesSection(List<GlossaryIssue> issues) {
        var sb = new StringBuilder();

        long major = issues.stream().filter(g -> "MAJOR".equalsIgnoreCase(g.severity())).count();
        long minor = issues.size() - major;
        sb.append("Rilevate \\textbf{%d} incoerenze terminologiche (%d maggiori, %d minori).\n\n"
                .formatted(issues.size(), major, minor));

        sb.append("\\begin{longtable}{p{3cm} p{4cm} c p{5cm}}\n");
        sb.append("\\toprule\n");
        sb.append("\\textbf{Gruppo} & \\textbf{Varianti trovate} & \\textbf{Severit\\`a} & \\textbf{Suggerimento} \\\\\n");
        sb.append("\\midrule\n");
        sb.append("\\endhead\n");

        for (GlossaryIssue g : issues) {
            String sevColor = "MAJOR".equalsIgnoreCase(g.severity()) ? "highcolor" : "medcolor";
            String variants = g.variants() != null ? escapeLatex(g.variants()) : "";

            sb.append("%s & {\\small %s} & \\textcolor{%s}{%s} & {\\small %s} \\\\\n".formatted(
                    escapeLatex(g.termGroup()),
                    variants,
                    sevColor,
                    escapeLatex(g.severity()),
                    escapeLatex(g.suggestion() != null ? g.suggestion() : "")));
        }

        sb.append("\\bottomrule\n");
        sb.append("\\end{longtable}\n\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════


    /**
     * Escapes LaTeX special characters for text INSIDE commands (\textit, \textbf, etc.).
     * Collapses newlines into spaces to avoid "Paragraph ended before \text@command".
     */
    private String escapeLatex(String text) {
        if (text == null) return "";
        String sanitized = text.replaceAll("\\r\\n", "\n")
                               .replaceAll("\\n{2,}", " ")
                               .replaceAll("\\n", " ");
        return escapeSpecialChars(sanitized);
    }

    /**
     * Escapes LaTeX special characters PRESERVING paragraphs.
     * For standalone text (not inside commands).
     */
    private String escapeLatexBlock(String text) {
        if (text == null) return "";
        String sanitized = text.replaceAll("\\r\\n", "\n");
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
