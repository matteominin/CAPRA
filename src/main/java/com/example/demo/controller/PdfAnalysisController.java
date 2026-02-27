package com.example.demo.controller;

import com.example.demo.model.AuditReport;
import com.example.demo.model.AuditResult;
import com.example.demo.model.PipelineTimings;
import com.example.demo.orchestrator.MultiAgentOrchestrator;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.service.FuzzyDiscardAccumulator;
import com.example.demo.service.TokenUsageAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.Map;

/**
 * REST controller for PDF document analysis.
 */
@RestController
@RequestMapping("/api")
public class PdfAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(PdfAnalysisController.class);

    private final MultiAgentOrchestrator orchestrator;
    private final DocumentIngestionService ingestionService;

    public PdfAnalysisController(MultiAgentOrchestrator orchestrator,
                                 DocumentIngestionService ingestionService) {
        this.orchestrator = orchestrator;
        this.ingestionService = ingestionService;
    }

    /**
     * Analyzes a PDF document and returns the audit report as a compiled PDF.
     * If LaTeX compilation fails, returns the .tex file.
     *
     * <p>Endpoint: POST /api/analyze
     * <p>Content-Type: multipart/form-data
     * <p>Parameter: file (PDF to analyze)
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyze(@RequestParam("file") MultipartFile file) {
        // ── Input validation ──
        if (file.isEmpty()) {
            return badRequest("Empty file. Please upload a valid PDF file.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return badRequest("Invalid format. Only PDF files accepted.");
        }
        if (file.getSize() > 50 * 1024 * 1024) {
            return badRequest("File too large. Maximum size: 50MB.");
        }

        log.info("Received analysis request for '{}' ({} bytes)", filename, file.getSize());

        TokenUsageAccumulator usage = TokenUsageAccumulator.start();
        FuzzyDiscardAccumulator fuzzy = FuzzyDiscardAccumulator.start();
        try {
            AuditResult result = orchestrator.analyze(file);

            long oaiIn  = usage.getOpenAiInputTokens();
            long oaiOut = usage.getOpenAiOutputTokens();
            long antIn  = usage.getAnthropicInputTokens();
            long antOut = usage.getAnthropicOutputTokens();
            log.info("Token usage — OpenAI: {}in/{}out (tot {}), Anthropic: {}in/{}out (tot {})",
                    oaiIn, oaiOut, oaiIn + oaiOut, antIn, antOut, antIn + antOut);

            HttpHeaders headers = responseHeaders(oaiIn, oaiOut, antIn, antOut, fuzzy, result.pipelineTimings());

            // If the PDF was compiled, return it
            if (result.pdfFile() != null && Files.exists(result.pdfFile())) {
                Resource resource = new FileSystemResource(result.pdfFile());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-report.pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .headers(headers)
                        .body(resource);
            }

            // Fallback: return the .tex file if PDF compilation failed
            if (result.texFile() != null && Files.exists(result.texFile())) {
                Resource resource = new FileSystemResource(result.texFile());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-report.tex\"")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Warning", "PDF compilation failed, returning LaTeX source file")
                        .headers(headers)
                        .body(resource);
            }

            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "No output file generated"));

        } catch (Exception e) {
            log.error("Error during analysis of '{}'", filename, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Error during analysis",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    ));
        } finally {
            TokenUsageAccumulator.clear();
            FuzzyDiscardAccumulator.clear();
        }
    }

    /**
     * Analyzes a PDF document and returns only the JSON report (without generating LaTeX/PDF).
     * Useful for debugging, integration with other systems, or when PDF is not needed.
     *
     * <p>Endpoint: POST /api/analyze/json
     */
    @PostMapping(value = "/analyze/json", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyzeJson(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return badRequest("Empty file. Please upload a valid PDF file.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return badRequest("Invalid format. Only PDF files accepted.");
        }

        log.info("Received JSON analysis request for '{}' ({} bytes)", filename, file.getSize());

        FuzzyDiscardAccumulator.start();
        try {
            AuditResult result = orchestrator.analyze(file);
            AuditReport report = result.report();
            FuzzyDiscardAccumulator fuzzy = FuzzyDiscardAccumulator.current();
            return ResponseEntity.ok()
                    .header("X-Fuzzy-Discarded-Count", fuzzy != null ? String.valueOf(fuzzy.count()) : "0")
                    .header("X-Fuzzy-Discarded-Issues", fuzzy != null ? fuzzy.asHeaderValue(1800) : "")
                    .header("X-Fuzzy-Discarded-Json-B64", fuzzy != null ? fuzzy.asJsonBase64(120) : "")
                    .body(report);

        } catch (Exception e) {
            log.error("Error during JSON analysis of '{}'", filename, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Error during analysis",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    ));
        } finally {
            FuzzyDiscardAccumulator.clear();
        }
    }

    /**
     * Checks the status of dependent services (Flask PDF extractor).
     *
     * <p>Endpoint: GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean pdfServiceUp = ingestionService.isServiceAvailable();
        return ResponseEntity.ok(Map.of(
                "status", pdfServiceUp ? "ok" : "degraded",
                "service", "SWE-Audit-Agent",
                "pdfExtractor", pdfServiceUp ? "up" : "down"
        ));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private HttpHeaders responseHeaders(long oaiIn, long oaiOut, long antIn, long antOut,
                                        FuzzyDiscardAccumulator fuzzy, PipelineTimings timings) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-OpenAI-Input-Tokens", String.valueOf(oaiIn));
        h.set("X-OpenAI-Output-Tokens", String.valueOf(oaiOut));
        h.set("X-OpenAI-Total-Tokens", String.valueOf(oaiIn + oaiOut));
        h.set("X-Anthropic-Input-Tokens", String.valueOf(antIn));
        h.set("X-Anthropic-Output-Tokens", String.valueOf(antOut));
        h.set("X-Anthropic-Total-Tokens", String.valueOf(antIn + antOut));
        h.set("X-Fuzzy-Discarded-Count", String.valueOf(fuzzy.count()));
        h.set("X-Fuzzy-Discarded-Issues", fuzzy.asHeaderValue(1800));
        String b64 = fuzzy.asJsonBase64(120);
        if (b64 != null && !b64.isEmpty()) {
            h.set("X-Fuzzy-Discarded-Json-B64", b64);
        }
        if (timings != null) {
            h.set("X-Pipeline-Stage-Extraction-Seconds", String.valueOf(timings.documentExtractionSeconds()));
            h.set("X-Pipeline-Stage-ParallelAgents-Seconds", String.valueOf(timings.parallelAgentsSeconds()));
            h.set("X-Pipeline-Stage-EvidenceDedup-Seconds", String.valueOf(timings.evidenceAndDedupSeconds()));
            h.set("X-Pipeline-Stage-ReportGen-Seconds", String.valueOf(timings.reportGenerationSeconds()));
            h.set("X-Pipeline-Total-Seconds", String.valueOf(timings.totalSeconds()));
        }
        return h;
    }
}
