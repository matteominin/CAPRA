package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proprietà di configurazione per il sistema di audit.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
        PdfService pdfService,
        Latex latex
) {

    /**
     * Configurazione del servizio Flask di estrazione PDF.
     *
     * @param baseUrl URL base del servizio (es. http://localhost:5001)
     */
    public record PdfService(String baseUrl) {}

    /**
     * Configurazione della generazione LaTeX.
     *
     * @param outputDir    Directory di output per i file generati
     * @param pdflatexPath Percorso dell'eseguibile pdflatex
     */
    public record Latex(String outputDir, String pdflatexPath) {}
}
