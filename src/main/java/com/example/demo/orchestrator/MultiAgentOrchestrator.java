package com.example.demo.orchestrator;

import com.example.demo.agent.*;
import com.example.demo.model.*;
import com.example.demo.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Multi-agent pipeline orchestrator.
 * Pipeline:
 * 1. Text extraction (Flask)
 * 2a. Parallel extraction: UseCaseExtractor + RequirementExtractor
 * 2b. Parallel analysis: Requirements + TestAuditor + Architecture + Features + Glossary
 * 3. Traceability mapping (uses extracted UCs/Reqs as context)
 * 4. Evidence Anchoring (fuzzy match, NO LLM)
 * 5. Confidence filtering (discard confidence &lt; threshold)
 * 6. LLM cross-verification (ConsistencyManager)
 * 7. Report normalization (ReportNormalizer)
 * 8. LaTeX report generation
 * 9. PDF compilation (pdflatex)
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    /** Minimum confidence threshold for including an issue in the report. */
    private static final double CONFIDENCE_THRESHOLD = 0.75;

    /** Deterministic issue ordering: category → severity (HIGH first) → page → description. */
    private static final Comparator<AuditIssue> ISSUE_ORDER = Comparator
            .comparing((AuditIssue i) -> i.category() != null ? i.category() : "ZZZ")
            .thenComparing(i -> i.severity().ordinal())
            .thenComparingInt(AuditIssue::pageReference)
            .thenComparing(i -> i.description() != null ? i.description() : "");

    private final DocumentIngestionService ingestionService;
    private final RequirementsAgent requirementsAgent;
    private final TestAuditorAgent testAuditorAgent;
    private final ArchitectureAgent architectureAgent;
    private final FeatureCheckAgent featureCheckAgent;
    private final TraceabilityMatrixAgent traceabilityAgent;
    private final UseCaseExtractorAgent useCaseExtractorAgent;
    private final RequirementExtractorAgent requirementExtractorAgent;
    private final EvidenceAnchoringService evidenceAnchoring;
    private final ConsistencyManager consistencyManager;
    private final ReportNormalizer reportNormalizer;
    private final LatexReportService latexReportService;
    private final LatexCompilerService latexCompilerService;
    private final ExecutorService agentExecutor;

    public MultiAgentOrchestrator(DocumentIngestionService ingestionService,
                                  RequirementsAgent requirementsAgent,
                                  TestAuditorAgent testAuditorAgent,
                                  ArchitectureAgent architectureAgent,
                                  FeatureCheckAgent featureCheckAgent,
                                  TraceabilityMatrixAgent traceabilityAgent,
                                  UseCaseExtractorAgent useCaseExtractorAgent,
                                  RequirementExtractorAgent requirementExtractorAgent,
                                  EvidenceAnchoringService evidenceAnchoring,
                                  ConsistencyManager consistencyManager,
                                  ReportNormalizer reportNormalizer,
                                  LatexReportService latexReportService,
                                  LatexCompilerService latexCompilerService,
                                  ExecutorService agentExecutor) {
        this.ingestionService = ingestionService;
        this.requirementsAgent = requirementsAgent;
        this.testAuditorAgent = testAuditorAgent;
        this.architectureAgent = architectureAgent;
        this.featureCheckAgent = featureCheckAgent;
        this.traceabilityAgent = traceabilityAgent;
        this.glossaryAgent = glossaryAgent;
        this.useCaseExtractorAgent = useCaseExtractorAgent;
        this.requirementExtractorAgent = requirementExtractorAgent;
        this.evidenceAnchoring = evidenceAnchoring;
        this.consistencyManager = consistencyManager;
        this.reportNormalizer = reportNormalizer;
        this.latexReportService = latexReportService;
        this.latexCompilerService = latexCompilerService;
        this.agentExecutor = agentExecutor;
    }

    public AuditResult analyze(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        log.info("═══════════════════════════════════════════════");
        log.info("Starting audit pipeline for '{}'", filename);
        log.info("═══════════════════════════════════════════════");

        // ── Step 1: Text extraction ──
        log.info("[1/9] Extracting text from PDF...");
        String fullText = ingestionService.extractFullText(file);
        log.info("[1/9] Extraction completed: {} characters", fullText.length());

        // ── Step 2: Parallel analysis — ALL agents + extractors run together ──
        log.info("[2/9] Starting parallel analysis (6 agents + 2 extractors)...");

        CompletableFuture<AgentResponse> reqFuture =
                CompletableFuture.supplyAsync(() -> requirementsAgent.analyze(fullText), agentExecutor);
        CompletableFuture<AgentResponse> testFuture =
                CompletableFuture.supplyAsync(() -> testAuditorAgent.analyze(fullText), agentExecutor);
        CompletableFuture<AgentResponse> archFuture =
                CompletableFuture.supplyAsync(() -> architectureAgent.analyze(fullText), agentExecutor);
        CompletableFuture<List<FeatureCoverage>> featureFuture =
                CompletableFuture.supplyAsync(() -> featureCheckAgent.checkFeatures(fullText), agentExecutor);
        CompletableFuture<List<GlossaryIssue>> glossaryFuture =
                CompletableFuture.supplyAsync(() -> glossaryAgent.analyze(fullText), agentExecutor);
        CompletableFuture<List<UseCaseEntry>> ucFuture =
                CompletableFuture.supplyAsync(() -> useCaseExtractorAgent.extract(fullText), agentExecutor);
        CompletableFuture<List<RequirementEntry>> reqExtractFuture =
                CompletableFuture.supplyAsync(() -> requirementExtractorAgent.extract(fullText), agentExecutor);

        AgentResponse reqResponse = reqFuture.join();
        AgentResponse testResponse = testFuture.join();
        AgentResponse archResponse = archFuture.join();
        List<FeatureCoverage> featureCoverage = featureFuture.join();
        List<GlossaryIssue> glossaryIssues = glossaryFuture.join();
        List<UseCaseEntry> extractedUCs = ucFuture.join();
        List<RequirementEntry> extractedReqs = reqExtractFuture.join();

        log.info("[2/9] Analysis completed — REQ: {} issues, TST: {} issues, ARCH: {} issues, " +
                        "Features: {}, Glossary: {} issues, UCs extracted: {}, Reqs extracted: {}",
                reqResponse.issues().size(), testResponse.issues().size(), archResponse.issues().size(),
                featureCoverage.size(), glossaryIssues.size(),
                extractedUCs.size(), extractedReqs.size());

        // ── Step 3: Traceability mapping (uses extracted UCs/Reqs as context) ──
        log.info("[3/9] Building traceability matrix (with {} UCs and {} Reqs as context)...",
                extractedUCs.size(), extractedReqs.size());
        List<TraceabilityEntry> traceability = traceabilityAgent.buildMatrix(
                fullText, extractedUCs, extractedReqs);
        log.info("[3/9] Traceability completed: {} entries", traceability.size());

        // ── Step 4: Evidence Anchoring (fuzzy match, NO LLM) ──
        log.info("[4/9] Evidence Anchoring (quote verification via fuzzy match)...");
        List<AuditIssue> allCandidates = Stream.of(
                reqResponse.issues().stream(),
                testResponse.issues().stream(),
                archResponse.issues().stream()
        ).flatMap(s -> s).sorted(ISSUE_ORDER).toList();

        List<AuditIssue> anchoredIssues = evidenceAnchoring.anchorEvidence(fullText, allCandidates);
        log.info("[4/9] Evidence Anchoring completed: {}/{} issues survived",
                anchoredIssues.size(), allCandidates.size());

        // ── Step 5: Confidence filtering ──
        log.info("[5/9] Filtering by confidence (threshold {})...", CONFIDENCE_THRESHOLD);
        List<AuditIssue> confidentIssues = anchoredIssues.stream()
                .filter(i -> i.confidenceScore() >= CONFIDENCE_THRESHOLD)
                .toList();
        long filtered = anchoredIssues.size() - confidentIssues.size();
        log.info("[5/9] {} issues with sufficient confidence ({} discarded below threshold)",
                confidentIssues.size(), filtered);

        // ── Step 6: LLM verification (ConsistencyManager) ──
        log.info("[6/9] LLM cross-verification on {} issues...", confidentIssues.size());
        List<AuditIssue> verifiedIssues = consistencyManager.verify(fullText, confidentIssues)
                .stream().sorted(ISSUE_ORDER).toList();
        log.info("[6/9] Verification completed: {} issues confirmed", verifiedIssues.size());

        // ── Step 7: Report normalization ──
        log.info("[7/9] Normalizing report...");
        List<AuditIssue> normalizedIssues = reportNormalizer.normalizeIssues(verifiedIssues, fullText);
        List<FeatureCoverage> missingFeatures = reportNormalizer.filterMissingFeatures(featureCoverage);
        Map<String, String> extractionCompleteness = reportNormalizer.computeExtractionCompleteness(
                normalizedIssues, featureCoverage);
        log.info("[7/9] Normalization completed: {} issues, {} missing features, {} completeness metrics",
                normalizedIssues.size(), missingFeatures.size(), extractionCompleteness.size());

        // ── Step 8: Report generation ──
        log.info("[8/9] Generating LaTeX report...");
        AuditReport report = AuditReport.from(filename, normalizedIssues,
                featureCoverage, traceability, glossaryIssues, extractedUCs, extractedReqs);
        Path texFile = latexReportService.generateReport(report, fullText,
                missingFeatures, featureCoverage, extractionCompleteness);
        log.info("[8/9] LaTeX report generated: {}", texFile);

        // ── Step 9: PDF compilation ──
        Path pdfFile = null;
        try {
            log.info("[9/9] Compiling PDF with pdflatex...");
            pdfFile = latexCompilerService.compile(texFile);
            log.info("[9/9] PDF compiled: {}", pdfFile);
        } catch (Exception e) {
            log.warn("[9/9] PDF compilation failed (.tex file is still available): {}", e.getMessage());
        }

        log.info("═══════════════════════════════════════════════");
        log.info("Pipeline completed: {} issues, {} features ({} missing), {} traceability, {} glossary, {} UCs, {} Reqs",
                report.totalIssues(), featureCoverage.size(), missingFeatures.size(),
                traceability.size(), glossaryIssues.size(), extractedUCs.size(), extractedReqs.size());
        log.info("═══════════════════════════════════════════════");

        return new AuditResult(report, texFile, pdfFile);
    }
}
