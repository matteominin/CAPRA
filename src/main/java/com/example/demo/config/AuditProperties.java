package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the audit system.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
        PdfService pdfService,
        Latex latex
) {

    /**
     * Configuration for the Flask PDF extraction service.
     *
     * @param baseUrl base URL of the service (e.g. http://localhost:5001)
     */
    public record PdfService(String baseUrl) {}

    /**
     * Configuration for LaTeX generation.
     *
     * @param outputDir    Output directory for generated files
     * @param pdflatexPath Path to the pdflatex executable
     */
    public record Latex(String outputDir, String pdflatexPath) {}
}
