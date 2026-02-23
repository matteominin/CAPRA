package com.example.demo.orchestrator;

import com.example.demo.agent.*;
import com.example.demo.model.*;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.service.EvidenceAnchoringService;
import com.example.demo.service.LatexCompilerService;
import com.example.demo.service.LatexReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Orchestratore della pipeline multi-agente.
 * Pipeline:
 * 1. Estrazione testo (Flask)
 * 2. Analisi parallela: Requirements + TestAuditor + Features + Traceability + Glossary
 * 3. Evidence Anchoring (fuzzy match, NO LLM)
 * 4. Confidence filtering (scarta confidence < 0.5)
 * 5. Verifica incrociata LLM (ConsistencyManager)
 * 6. Generazione report LaTeX
 * 7. Compilazione PDF (pdflatex)
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    /** Soglia minima di confidence per includere un'issue nel report. */
    private static final double CONFIDENCE_THRESHOLD = 0.5;

    /** Ordine deterministico delle issue: categoria → severità (HIGH first) → pagina → descrizione. */
    private static final Comparator<AuditIssue> ISSUE_ORDER = Comparator
            .comparing((AuditIssue i) -> i.category() != null ? i.category() : "ZZZ")
            .thenComparing(i -> i.severity().ordinal())
            .thenComparingInt(AuditIssue::pageReference)
            .thenComparing(i -> i.description() != null ? i.description() : "");

    private final DocumentIngestionService ingestionService;
    private final RequirementsAgent requirementsAgent;
    private final TestAuditorAgent testAuditorAgent;
    private final FeatureCheckAgent featureCheckAgent;
    private final TraceabilityMatrixAgent traceabilityAgent;
    private final GlossaryConsistencyAgent glossaryAgent;
    private final EvidenceAnchoringService evidenceAnchoring;
    private final ConsistencyManager consistencyManager;
    private final LatexReportService latexReportService;
    private final LatexCompilerService latexCompilerService;
    private final ExecutorService agentExecutor;

    public MultiAgentOrchestrator(DocumentIngestionService ingestionService,
                                  RequirementsAgent requirementsAgent,
                                  TestAuditorAgent testAuditorAgent,
                                  FeatureCheckAgent featureCheckAgent,
                                  TraceabilityMatrixAgent traceabilityAgent,
                                  GlossaryConsistencyAgent glossaryAgent,
                                  EvidenceAnchoringService evidenceAnchoring,
                                  ConsistencyManager consistencyManager,
                                  LatexReportService latexReportService,
                                  LatexCompilerService latexCompilerService,
                                  ExecutorService agentExecutor) {
        this.ingestionService = ingestionService;
        this.requirementsAgent = requirementsAgent;
        this.testAuditorAgent = testAuditorAgent;
        this.featureCheckAgent = featureCheckAgent;
        this.traceabilityAgent = traceabilityAgent;
        this.glossaryAgent = glossaryAgent;
        this.evidenceAnchoring = evidenceAnchoring;
        this.consistencyManager = consistencyManager;
        this.latexReportService = latexReportService;
        this.latexCompilerService = latexCompilerService;
        this.agentExecutor = agentExecutor;
    }

    public AuditResult analyze(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        log.info("═══════════════════════════════════════════════");
        log.info("Avvio pipeline di audit per '{}'", filename);
        log.info("═══════════════════════════════════════════════");

        // ── Step 1: Estrazione testo ──
        log.info("[1/7] Estrazione testo dal PDF...");
        String fullText = ingestionService.extractFullText(file);
        log.info("[1/7] Estrazione completata: {} caratteri", fullText.length());

        // ── Step 2: Analisi parallela con TUTTI gli agenti ──
        log.info("[2/7] Avvio analisi parallela (5 agenti)...");

        CompletableFuture<AgentResponse> reqFuture =
                CompletableFuture.supplyAsync(() -> requirementsAgent.analyze(fullText), agentExecutor);
        CompletableFuture<AgentResponse> testFuture =
                CompletableFuture.supplyAsync(() -> testAuditorAgent.analyze(fullText), agentExecutor);
        CompletableFuture<List<FeatureCoverage>> featureFuture =
                CompletableFuture.supplyAsync(() -> featureCheckAgent.checkFeatures(fullText), agentExecutor);
        CompletableFuture<List<TraceabilityEntry>> traceFuture =
                CompletableFuture.supplyAsync(() -> traceabilityAgent.buildMatrix(fullText), agentExecutor);
        CompletableFuture<List<GlossaryIssue>> glossaryFuture =
                CompletableFuture.supplyAsync(() -> glossaryAgent.analyze(fullText), agentExecutor);

        AgentResponse reqResponse = reqFuture.join();
        AgentResponse testResponse = testFuture.join();
        List<FeatureCoverage> featureCoverage = featureFuture.join();
        List<TraceabilityEntry> traceability = traceFuture.join();
        List<GlossaryIssue> glossaryIssues = glossaryFuture.join();

        log.info("[2/7] Analisi completata — REQ: {} issues, TST: {} issues, Features: {}, " +
                        "Traceability: {} entries, Glossary: {} issues",
                reqResponse.issues().size(), testResponse.issues().size(),
                featureCoverage.size(), traceability.size(), glossaryIssues.size());

        // ── Step 3: Evidence Anchoring (fuzzy match, NO LLM) ──
        log.info("[3/7] Evidence Anchoring (verifica citazioni con fuzzy match)...");
        List<AuditIssue> allCandidates = Stream.concat(
                reqResponse.issues().stream(),
                testResponse.issues().stream()
        ).sorted(ISSUE_ORDER).toList();

        List<AuditIssue> anchoredIssues = evidenceAnchoring.anchorEvidence(fullText, allCandidates);
        log.info("[3/7] Evidence Anchoring completato: {}/{} issues sopravvissute",
                anchoredIssues.size(), allCandidates.size());

        // ── Step 4: Confidence filtering ──
        log.info("[4/7] Filtraggio per confidence (soglia {})...", CONFIDENCE_THRESHOLD);
        List<AuditIssue> confidentIssues = anchoredIssues.stream()
                .filter(i -> i.confidenceScore() >= CONFIDENCE_THRESHOLD)
                .toList();
        long filtered = anchoredIssues.size() - confidentIssues.size();
        log.info("[4/7] {} issues con confidence sufficiente ({} scartate sotto soglia)",
                confidentIssues.size(), filtered);

        // ── Step 5: Verifica LLM (ConsistencyManager) ──
        log.info("[5/7] Verifica incrociata LLM su {} issues...", confidentIssues.size());
        List<AuditIssue> verifiedIssues = consistencyManager.verify(fullText, confidentIssues)
                .stream().sorted(ISSUE_ORDER).toList();
        log.info("[5/7] Verifica completata: {} issues confermate", verifiedIssues.size());

        // ── Step 6: Generazione report ──
        log.info("[6/7] Generazione report LaTeX...");
        AuditReport report = AuditReport.from(filename, verifiedIssues,
                featureCoverage, traceability, glossaryIssues);
        Path texFile = latexReportService.generateReport(report, fullText);
        log.info("[6/7] Report LaTeX generato: {}", texFile);

        // ── Step 7: Compilazione PDF ──
        Path pdfFile = null;
        try {
            log.info("[7/7] Compilazione PDF con pdflatex...");
            pdfFile = latexCompilerService.compile(texFile);
            log.info("[7/7] PDF compilato: {}", pdfFile);
        } catch (Exception e) {
            log.warn("[7/7] Compilazione PDF fallita (il file .tex è comunque disponibile): {}", e.getMessage());
        }

        log.info("═══════════════════════════════════════════════");
        log.info("Pipeline completata: {} issues, {} features, {} traceability, {} glossary",
                report.totalIssues(), featureCoverage.size(), traceability.size(), glossaryIssues.size());
        log.info("═══════════════════════════════════════════════");

        return new AuditResult(report, texFile, pdfFile);
    }
}
