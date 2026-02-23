package com.example.demo.model;

import java.nio.file.Path;

/**
 * Risultato completo della pipeline di audit: report + file generati.
 *
 * @param report  Report strutturato dell'audit
 * @param texFile Percorso del file LaTeX generato
 * @param pdfFile Percorso del PDF compilato (null se la compilazione è fallita)
 */
public record AuditResult(
        AuditReport report,
        Path texFile,
        Path pdfFile
) {}
