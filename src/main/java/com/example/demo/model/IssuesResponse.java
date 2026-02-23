package com.example.demo.model;

import java.util.List;

/**
 * Wrapper per il parsing strutturato della risposta degli agenti di analisi.
 * Usato da Spring AI BeanOutputConverter per forzare output JSON strutturato.
 */
public record IssuesResponse(List<AuditIssue> issues) {}
