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
 * Compiles LaTeX (.tex) files to PDF via pdflatex.
 * Runs two passes to correctly resolve the table of contents and cross-references.
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
     * Compiles the .tex file to PDF.
     *
     * @param texFile path of the LaTeX file to compile
     * @return path of the generated PDF
     * @throws RuntimeException if compilation fails or times out
     */
    public Path compile(Path texFile) {
        log.info("Starting LaTeX compilation: {}", texFile);

        String pdflatexPath = properties.latex().pdflatexPath();
        Path workDir = texFile.getParent();

        // Verify that pdflatex is available
        verifyPdflatexInstalled(pdflatexPath);

        try {
            // Run pdflatex twice: the first generates the .toc, the second includes it
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
                    throw new RuntimeException("pdflatex timeout after %d seconds".formatted(TIMEOUT_SECONDS));
                }

                if (process.exitValue() != 0) {
                    String errorDetail = extractLatexError(output);
                    log.error("pdflatex error (pass {}): {}", pass, errorDetail);
                    throw new RuntimeException(
                            "LaTeX compilation failed (exit code: %d): %s"
                                    .formatted(process.exitValue(), errorDetail));
                }

                log.debug("pdflatex pass {} completed successfully", pass);
            }

            // Verify that the PDF was generated
            String pdfName = texFile.getFileName().toString().replace(".tex", ".pdf");
            Path pdfFile = workDir.resolve(pdfName);

            if (!Files.exists(pdfFile)) {
                throw new RuntimeException("PDF file not generated after compilation: " + pdfFile);
            }

            long pdfSize = Files.size(pdfFile);
            log.info("PDF generated successfully: {} ({} bytes)", pdfFile, pdfSize);
            return pdfFile;

        } catch (IOException e) {
            throw new RuntimeException("I/O error during LaTeX compilation: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LaTeX compilation interrupted", e);
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
                        "pdflatex not available or not working. " +
                                "Install a LaTeX distribution (e.g. TeX Live, MacTeX).");
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "pdflatex not found in PATH. Install a LaTeX distribution (e.g. TeX Live, MacTeX). " +
                            "Searched path: " + pdflatexPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("pdflatex verification interrupted", e);
        }
    }

    /**
     * Extracts relevant error lines from pdflatex output.
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
            // Last 10 lines as fallback
            int start = Math.max(0, lines.length - 10);
            for (int i = start; i < lines.length; i++) {
                errors.append(lines[i].trim()).append("\n");
            }
        }
        return errors.toString().trim();
    }
}
