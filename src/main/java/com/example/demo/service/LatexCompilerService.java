package com.example.demo.service;

import com.example.demo.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Compila file LaTeX (.tex) in PDF tramite pdflatex.
 * Esegue due passaggi per risolvere correttamente il sommario e i riferimenti incrociati.
 */
@Service
public class LatexCompilerService {

    private static final Logger log = LoggerFactory.getLogger(LatexCompilerService.class);
    private static final int TIMEOUT_SECONDS = 120;

    private final AuditProperties properties;

    public LatexCompilerService(AuditProperties properties) {
        this.properties = properties;
    }

    /**
     * Compila il file .tex in PDF.
     *
     * @param texFile percorso del file LaTeX da compilare
     * @return percorso del PDF generato
     * @throws RuntimeException se la compilazione fallisce o va in timeout
     */
    public Path compile(Path texFile) {
        log.info("Avvio compilazione LaTeX: {}", texFile);

        String pdflatexPath = properties.latex().pdflatexPath();
        Path workDir = texFile.getParent();

        // Verifica che pdflatex sia disponibile
        verifyPdflatexInstalled(pdflatexPath);

        try {
            // Esegui pdflatex due volte: la prima genera il .toc, la seconda lo include
            for (int pass = 1; pass <= 2; pass++) {
                log.info("pdflatex pass {}/2", pass);

                ProcessBuilder pb = new ProcessBuilder(
                        pdflatexPath,
                        "-interaction=nonstopmode",
                        "-halt-on-error",
                        texFile.getFileName().toString()
                );
                pb.directory(workDir.toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("pdflatex timeout dopo %d secondi".formatted(TIMEOUT_SECONDS));
                }

                if (process.exitValue() != 0) {
                    String errorDetail = extractLatexError(output);
                    log.error("pdflatex errore (pass {}): {}", pass, errorDetail);
                    throw new RuntimeException(
                            "Compilazione LaTeX fallita (exit code: %d): %s"
                                    .formatted(process.exitValue(), errorDetail));
                }

                log.debug("pdflatex pass {} completato con successo", pass);
            }

            // Verifica che il PDF sia stato generato
            String pdfName = texFile.getFileName().toString().replace(".tex", ".pdf");
            Path pdfFile = workDir.resolve(pdfName);

            if (!Files.exists(pdfFile)) {
                throw new RuntimeException("File PDF non generato dopo la compilazione: " + pdfFile);
            }

            long pdfSize = Files.size(pdfFile);
            log.info("PDF generato con successo: {} ({} bytes)", pdfFile, pdfSize);
            return pdfFile;

        } catch (IOException e) {
            throw new RuntimeException("Errore I/O durante la compilazione LaTeX: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Compilazione LaTeX interrotta", e);
        }
    }

    private void verifyPdflatexInstalled(String pdflatexPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pdflatexPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                throw new RuntimeException(
                        "pdflatex non disponibile o non funzionante. " +
                                "Installare una distribuzione LaTeX (es. TeX Live, MacTeX).");
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "pdflatex non trovato nel PATH. Installare una distribuzione LaTeX (es. TeX Live, MacTeX). " +
                            "Percorso cercato: " + pdflatexPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Verifica pdflatex interrotta", e);
        }
    }

    /**
     * Estrae le righe di errore rilevanti dall'output di pdflatex.
     */
    private String extractLatexError(String output) {
        var errors = new StringBuilder();
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("!") || line.contains("Error") || line.contains("error")) {
                errors.append(line.trim()).append("; ");
            }
        }
        if (errors.isEmpty()) {
            // Ultime 10 righe come fallback
            int start = Math.max(0, lines.length - 10);
            for (int i = start; i < lines.length; i++) {
                errors.append(lines[i].trim()).append("\n");
            }
        }
        return errors.toString().trim();
    }
}
