package com.example.demo.service;

import com.example.demo.config.AuditProperties;
import com.example.demo.model.AuditIssue;
import com.example.demo.model.AuditReport;
import com.example.demo.model.FeatureCoverage;
import com.example.demo.model.Severity;
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
 * Genera il report di audit in formato LaTeX.
 * Usa Haiku 4.5 (Anthropic) per le sezioni narrative con fallback a GPT-5.1 (OpenAI).
 * <p>
 * Struttura del report:
 * 1. Contesto del Documento
 * 2. Sintesi Esecutiva
 * 3. Punti di Forza
 * 4. Scorecard per Area
 * 5. Copertura Feature (da MongoDB)
 * 6. Tabella Riassuntiva
 * 7. Dettaglio per Categoria (raggruppato per UC)
 * 8. Raccomandazioni Prioritarie
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
        log.info("Generazione report LaTeX per '{}' ({} issues, {} feature)",
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
            log.info("File LaTeX generato: {}", texFile);
            return texFile;
        } catch (IOException e) {
            throw new RuntimeException("Errore nella scrittura del file LaTeX: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════
    // LLM-generated sections
    // ═══════════════════════════════════════════════════

    private String generateDocumentContext(String fullText) {
        String contextPrompt = """
                Analizza il seguente testo di un documento di progetto software (tesi/relazione)
                e genera una panoramica strutturata IN TESTO SEMPLICE (NO comandi LaTeX).
                
                Devi estrarre e riassumere TUTTE le seguenti sezioni. NON troncare l'output.
                
                OBIETTIVO DEL PROGETTO: cosa fa l'applicazione in 2-3 frasi.
                
                CASI D'USO PRINCIPALI: elenca TUTTI i Use Case con formato "UC-N - Nome: descrizione breve".
                
                REQUISITI FUNZIONALI: elenca i requisiti funzionali chiave in modo sintetico.
                
                REQUISITI NON FUNZIONALI: elenca i requisiti non funzionali (performance, sicurezza, ecc.).
                
                ARCHITETTURA: descrivi brevemente l'architettura (pattern, tecnologie, struttura).
                
                STRATEGIA DI TESTING: descrivi brevemente come vengono testati i requisiti.
                
                REGOLE CRITICHE:
                - Scrivi in italiano
                - Sii CONCISO: massimo 2 righe per ogni punto, massimo 1 riga per ogni requisito/UC
                - NON usare linee decorative, intestazioni elaborate o separatori
                - NON usare caratteri speciali LaTeX (& % $ # _ { } ~ ^ \\)
                - Usa trattini (-) per gli elenchi
                - Se una sezione non e' presente nel documento, scrivi "Non descritto nel documento"
                - COMPLETA TUTTE LE SEZIONI: non fermarti a meta
                """;

        try {
            String context = callLlmWithFallback(contextPrompt,
                    "Ecco il testo del documento:\n\n" + truncateForPrompt(fullText, 80000));
            log.debug("Contesto documento generato ({} caratteri)", context.length());
            return context;
        } catch (Exception e) {
            log.warn("Impossibile generare contesto documento: {}", e.getMessage());
            return "Il contesto del documento non e' disponibile a causa di un errore nella generazione automatica.";
        }
    }

    private String generateExecutiveSummary(AuditReport report) {
        if (report.issues().isEmpty()) {
            return "L'analisi del documento non ha rilevato problemi significativi. "
                    + "Il documento appare ben strutturato e coerente.";
        }

        Map<String, List<AuditIssue>> byCategory = report.issues().stream()
                .collect(Collectors.groupingBy(i -> i.category() != null ? i.category() : "Altro",
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder issuesSummary = new StringBuilder();
        for (var entry : byCategory.entrySet()) {
            issuesSummary.append("\nCategoria %s (%d problemi):\n".formatted(entry.getKey(), entry.getValue().size()));
            for (AuditIssue i : entry.getValue()) {
                issuesSummary.append("- [%s] %s: %s (pag. %d)\n".formatted(
                        i.severity(), i.id(), i.description(), i.pageReference()));
            }
        }

        String systemPrompt = """
                Sei un esperto di Ingegneria del Software. Genera una sintesi esecutiva in italiano
                per un report di audit di un documento SWE scritto da uno studente universitario.
                La sintesi deve essere in TESTO SEMPLICE (NON usare comandi LaTeX).
                Deve essere concisa (3-5 paragrafi brevi) e deve:
                1. Inquadrare brevemente il documento e il suo scopo (2-3 frasi)
                2. Evidenziare i pattern di problemi riscontrati (non elencare ogni singolo problema)
                3. Indicare le aree critiche (severity HIGH)
                4. Dare una valutazione complessiva della qualita
                5. Suggerire le 3 azioni prioritarie
                
                REGOLE CRITICHE:
                - NON usare caratteri speciali: & % $ # _ { } ~ ^ \\
                - NON usare intestazioni decorate, separatori, linee di simboli
                - COMPLETA l'intera sintesi: non fermarti a meta
                - Massimo 500 parole totali
                """;

        String userPrompt = """
                Genera la sintesi esecutiva per l'audit del documento '%s'.
                Totale problemi trovati: %d (HIGH: %d, MEDIUM: %d, LOW: %d)
                
                %s""".formatted(
                report.documentName(),
                report.totalIssues(),
                report.severityDistribution().getOrDefault(Severity.HIGH, 0L),
                report.severityDistribution().getOrDefault(Severity.MEDIUM, 0L),
                report.severityDistribution().getOrDefault(Severity.LOW, 0L),
                issuesSummary);

        try {
            String summary = callLlmWithFallback(systemPrompt, userPrompt);
            log.debug("Sintesi esecutiva generata ({} caratteri)", summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("Impossibile generare sintesi esecutiva: {}", e.getMessage());
            return ("L'analisi del documento '%s' ha rilevato %d problemi, " +
                    "di cui %d ad alta severita. Si raccomanda una revisione approfondita " +
                    "delle aree critiche identificate nel report.").formatted(
                    report.documentName(),
                    report.totalIssues(),
                    report.severityDistribution().getOrDefault(Severity.HIGH, 0L));
        }
    }

    /**
     * Genera la sezione "Punti di Forza" — aspetti positivi del documento.
     */
    private String generateStrengths(AuditReport report, String fullText) {
        String systemPrompt = """
                Sei un esperto di Ingegneria del Software. Analizza un documento di progetto SWE
                e identifica i PUNTI DI FORZA del lavoro dello studente.
                
                Scrivi in TESTO SEMPLICE (NO comandi LaTeX). Scrivi in ITALIANO.
                
                Identifica 3-6 aspetti positivi del documento, ad esempio:
                - Struttura chiara e ben organizzata
                - Buona copertura dei casi d'uso
                - Testing approfondito
                - Documentazione dei requisiti completa
                - Architettura ben definita
                - Uso corretto di pattern di design
                
                REGOLE:
                - Ogni punto di forza deve essere specifico e basato su EVIDENZE nel testo
                - NON inventare punti di forza se non ci sono
                - Formato: un elenco con trattini (-), una riga per punto
                - NON usare caratteri speciali LaTeX: & % $ # _ { } ~ ^ \\
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
                truncateForPrompt(fullText, 30000));

        try {
            return callLlmWithFallback(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("Impossibile generare punti di forza: {}", e.getMessage());
            return "L'analisi dei punti di forza non e' disponibile.";
        }
    }

    private String callLlmWithFallback(String systemPrompt, String userPrompt) {
        try {
            String result = reportChatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (result != null && !result.isBlank()) {
                return result;
            }
            log.warn("Anthropic ha restituito risposta vuota, provo con OpenAI...");
        } catch (Exception e) {
            log.warn("Anthropic fallito ({}), provo con OpenAI...", e.getMessage());
        }

        String result = analysisChatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        return result != null ? result : "";
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
        sb.append(escapeLatexBlock(documentContext));
        sb.append("\n\n");

        // ── 2. Sintesi Esecutiva ──
        sb.append("\\section{Sintesi Esecutiva}\n");
        sb.append(escapeLatexBlock(summary));
        sb.append("\n\n");

        // ── 3. Punti di Forza ──
        sb.append("\\section{Punti di Forza}\n");
        sb.append(escapeLatexBlock(strengths));
        sb.append("\n\n");

        // ── 4. Scorecard per Area ──
        sb.append("\\section{Scorecard per Area}\n");
        sb.append(buildScorecard(report));

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

        sb.append("\\end{document}\n");
        return sb.toString();
    }

    /**
     * Scorecard — calcola un voto (A-F) per ogni categoria basandosi su numero e severita delle issue.
     * Formula: score = max(0, 100 - HIGH*25 - MEDIUM*10 - LOW*3)
     * A: 90-100, B: 75-89, C: 60-74, D: 40-59, F: 0-39
     */
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
     * Sezione feature coverage — tabella con stato di ogni feature e barra di copertura.
     */
    private String buildFeatureCoverageSection(List<FeatureCoverage> features) {
        var sb = new StringBuilder();

        // Conteggio riepilogo
        long present = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
        long partial = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
        long absent = features.stream().filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();
        int avgCoverage = (int) features.stream().mapToInt(FeatureCoverage::coverageScore).average().orElse(0);

        sb.append("Su %d feature attese: \\textcolor{present}{%d presenti}, \\textcolor{partial}{%d parziali}, \\textcolor{absent}{%d assenti}. "
                .formatted(features.size(), present, partial, absent));
        sb.append("Copertura media: \\textbf{%d\\%%}.\n\n".formatted(avgCoverage));

        // Tabella dettagliata
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
     * Tabella riassuntiva Categoria x Severita.
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
     * Dettaglio per categoria, con raggruppamento per Use Case dove rilevabile.
     * Issues che menzionano "UC-N" vengono raggruppate sotto quel caso d'uso.
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
     * Estrae il riferimento UC da una issue (cercando "UC-N" o "UC N" nella descrizione/quote).
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
     * Costruisce una mappa di cross-reference: per ogni issue, le issue correlate (stesso UC).
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
     * Blocco LaTeX per singola issue, con cross-reference e raccomandazione.
     */
    private String buildIssueBlock(AuditIssue issue, Map<String, List<String>> crossRefs) {
        String severityLabel = switch (issue.severity()) {
            case HIGH -> "\\textcolor{highcolor}{HIGH}";
            case MEDIUM -> "\\textcolor{medcolor}{MEDIUM}";
            case LOW -> "\\textcolor{lowcolor}{LOW}";
        };

        var sb = new StringBuilder();
        sb.append("\\paragraph{%s \\textnormal{--- %s --- Pagina %d}}\n".formatted(
                escapeLatex(issue.id()), severityLabel, issue.pageReference()));

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
     * Raccomandazioni prioritarie — solo HIGH, una riga ciascuna con ID e azione.
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
     * Tronca una raccomandazione a maxLen caratteri, tagliando alla fine della frase piu vicina.
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
    // Utility
    // ═══════════════════════════════════════════════════

    private String truncateForPrompt(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        log.warn("Testo troncato da {} a {} caratteri per il prompt", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... testo troncato per limiti di contesto ...]";
    }

    /**
     * Escapa i caratteri speciali LaTeX per testo DENTRO comandi (\textit, \textbf, etc.).
     * Collassa newline in spazi per evitare "Paragraph ended before \text@command".
     */
    private String escapeLatex(String text) {
        if (text == null) return "";
        String sanitized = text.replaceAll("\\r\\n", "\n")
                               .replaceAll("\\n{2,}", " ")
                               .replaceAll("\\n", " ");
        return escapeSpecialChars(sanitized);
    }

    /**
     * Escapa i caratteri speciali LaTeX PRESERVANDO i paragrafi.
     * Per testo standalone (non dentro comandi).
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
