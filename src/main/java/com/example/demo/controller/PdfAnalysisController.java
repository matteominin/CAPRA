package com.example.demo.controller;

import com.example.demo.model.AuditReport;
import com.example.demo.model.AuditResult;
import com.example.demo.orchestrator.MultiAgentOrchestrator;
import com.example.demo.service.DocumentIngestionService;
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
 * Controller REST per l'analisi dei documenti PDF.
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
     * Analizza un documento PDF e restituisce il report di audit come PDF compilato.
     * Se la compilazione LaTeX fallisce, restituisce il file .tex.
     *
     * <p>Endpoint: POST /api/analyze
     * <p>Content-Type: multipart/form-data
     * <p>Parametro: file (PDF da analizzare)
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyze(@RequestParam("file") MultipartFile file) {
        // ── Validazione input ──
        if (file.isEmpty()) {
            return badRequest("File vuoto. Caricare un file PDF valido.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return badRequest("Formato non valido. Solo file PDF accettati.");
        }
        if (file.getSize() > 50 * 1024 * 1024) {
            return badRequest("File troppo grande. Dimensione massima: 50MB.");
        }

        log.info("Ricevuta richiesta di analisi per '{}' ({} bytes)", filename, file.getSize());

        try {
            AuditResult result = orchestrator.analyze(file);

            // Se il PDF è stato compilato, restituiscilo
            if (result.pdfFile() != null && Files.exists(result.pdfFile())) {
                Resource resource = new FileSystemResource(result.pdfFile());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-report.pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(resource);
            }

            // Fallback: restituisci il file .tex se la compilazione PDF è fallita
            if (result.texFile() != null && Files.exists(result.texFile())) {
                Resource resource = new FileSystemResource(result.texFile());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-report.tex\"")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Warning", "Compilazione PDF fallita, restituito file LaTeX sorgente")
                        .body(resource);
            }

            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Nessun file di output generato"));

        } catch (Exception e) {
            log.error("Errore durante l'analisi di '{}'", filename, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Errore durante l'analisi",
                            "message", e.getMessage() != null ? e.getMessage() : "Errore sconosciuto"
                    ));
        }
    }

    /**
     * Analizza un documento PDF e restituisce solo il report JSON (senza generare LaTeX/PDF).
     * Utile per debug, integrazione con altri sistemi, o quando non serve il PDF.
     *
     * <p>Endpoint: POST /api/analyze/json
     */
    @PostMapping(value = "/analyze/json", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyzeJson(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return badRequest("File vuoto. Caricare un file PDF valido.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return badRequest("Formato non valido. Solo file PDF accettati.");
        }

        log.info("Ricevuta richiesta di analisi JSON per '{}' ({} bytes)", filename, file.getSize());

        try {
            AuditResult result = orchestrator.analyze(file);
            AuditReport report = result.report();
            return ResponseEntity.ok(report);

        } catch (Exception e) {
            log.error("Errore durante l'analisi JSON di '{}'", filename, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Errore durante l'analisi",
                            "message", e.getMessage() != null ? e.getMessage() : "Errore sconosciuto"
                    ));
        }
    }

    /**
     * Verifica lo stato dei servizi dipendenti (Flask PDF extractor).
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
}
