package com.example.demo.orchestrator;

import com.example.demo.agent.ConsistencyManager;
import com.example.demo.agent.FeatureCheckAgent;
import com.example.demo.agent.RequirementsAgent;
import com.example.demo.agent.TestAuditorAgent;
import com.example.demo.model.*;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.service.LatexCompilerService;
import com.example.demo.service.LatexReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Orchestratore della pipeline multi-agente.
 * Coordina l'intera analisi: estrazione → analisi parallela (+ feature check) → verifica → report → compilazione.
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final DocumentIngestionService ingestionService;
    private final RequirementsAgent requirementsAgent;
    private final TestAuditorAgent testAuditorAgent;
    private final FeatureCheckAgent featureCheckAgent;
    private final ConsistencyManager consistencyManager;
    private final LatexReportService latexReportService;
    private final LatexCompilerService latexCompilerService;
    private final ExecutorService agentExecutor;

    public MultiAgentOrchestrator(DocumentIngestionService ingestionService,
                                  RequirementsAgent requirementsAgent,
                                  TestAuditorAgent testAuditorAgent,
                                  FeatureCheckAgent featureCheckAgent,
                                  ConsistencyManager consistencyManager,
                                  LatexReportService latexReportService,
                                  LatexCompilerService latexCompilerService,
                                  ExecutorService agentExecutor) {
        this.ingestionService = ingestionService;
        this.requirementsAgent = requirementsAgent;
        this.testAuditorAgent = testAuditorAgent;
        this.featureCheckAgent = featureCheckAgent;
        this.consistencyManager = consistencyManager;
        this.latexReportService = latexReportService;
        this.latexCompilerService = latexCompilerService;
        this.agentExecutor = agentExecutor;
    }

    /**
     * Esegue la pipeline completa di audit su un file PDF.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Estrazione testo completo (via servizio Flask)</li>
     *   <li>Analisi parallela: RequirementsAgent + TestAuditorAgent + FeatureCheckAgent</li>
     *   <li>Verifica incrociata: ConsistencyManager (Verification Loop)</li>
     *   <li>Generazione report LaTeX (Haiku 4.5)</li>
     *   <li>Compilazione PDF (pdflatex)</li>
     * </ol>
     *
     * @param file il PDF caricato dall'utente
     * @return risultato completo con report, file .tex e file .pdf
     */
    public AuditResult analyze(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        log.info("═══════════════════════════════════════════════");
        log.info("Avvio pipeline di audit per '{}'", filename);
        log.info("═══════════════════════════════════════════════");

        // ── Step 1: Estrazione testo ──
        log.info("[1/5] Estrazione testo dal PDF...");
        String fullText = ingestionService.extractFullText(file);
        log.info("[1/5] Estrazione completata: {} caratteri", fullText.length());

        // ── Step 2: Analisi parallela con gli agenti + feature check ──
        log.info("[2/5] Avvio analisi parallela (RequirementsAgent + TestAuditorAgent + FeatureCheckAgent)...");

        CompletableFuture<AgentResponse> reqFuture =
                CompletableFuture.supplyAsync(() -> requirementsAgent.analyze(fullText), agentExecutor);
        CompletableFuture<AgentResponse> testFuture =
                CompletableFuture.supplyAsync(() -> testAuditorAgent.analyze(fullText), agentExecutor);
        CompletableFuture<List<FeatureCoverage>> featureFuture =
                CompletableFuture.supplyAsync(() -> featureCheckAgent.checkFeatures(fullText), agentExecutor);

        // Attendi il completamento di tutti
        AgentResponse reqResponse = reqFuture.join();
        AgentResponse testResponse = testFuture.join();
        List<FeatureCoverage> featureCoverage = featureFuture.join();

        log.info("[2/5] Analisi completata — RequirementsAgent: {} issues, TestAuditorAgent: {} issues, Features: {} verificate",
                reqResponse.issues().size(), testResponse.issues().size(), featureCoverage.size());

        // ── Step 3: Merge e verifica (Verification Loop) ──
        List<AuditIssue> allCandidates = Stream.concat(
                reqResponse.issues().stream(),
                testResponse.issues().stream()
        ).toList();

        log.info("[3/5] Avvio verifica di coerenza su {} issues candidate...", allCandidates.size());
        List<AuditIssue> verifiedIssues = consistencyManager.verify(fullText, allCandidates);
        log.info("[3/5] Verifica completata: {} issues confermate su {} candidate",
                verifiedIssues.size(), allCandidates.size());

        // ── Step 4: Generazione report ──
        log.info("[4/5] Generazione report LaTeX...");
        AuditReport report = AuditReport.from(filename, verifiedIssues, featureCoverage);
        Path texFile = latexReportService.generateReport(report, fullText);
        log.info("[4/5] Report LaTeX generato: {}", texFile);

        // ── Step 5: Compilazione PDF ──
        Path pdfFile = null;
        try {
            log.info("[5/5] Compilazione PDF con pdflatex...");
            pdfFile = latexCompilerService.compile(texFile);
            log.info("[5/5] PDF compilato: {}", pdfFile);
        } catch (Exception e) {
            log.warn("[5/5] Compilazione PDF fallita (il file .tex è comunque disponibile): {}", e.getMessage());
        }

        log.info("═══════════════════════════════════════════════");
        log.info("Pipeline completata per '{}': {} issues, {} features verificate", filename, report.totalIssues(), featureCoverage.size());
        log.info("═══════════════════════════════════════════════");

        return new AuditResult(report, texFile, pdfFile);
    }
}
