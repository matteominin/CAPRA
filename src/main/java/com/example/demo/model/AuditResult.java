package com.example.demo.model;

import java.nio.file.Path;

/**
 * Complete result of the audit pipeline: report + generated files.
 *
 * @param report  Structured audit report
 * @param texFile Path of the generated LaTeX file
 * @param pdfFile Path of the compiled PDF (null if compilation failed)
 */
public record AuditResult(
        AuditReport report,
        Path texFile,
        Path pdfFile
) {}
