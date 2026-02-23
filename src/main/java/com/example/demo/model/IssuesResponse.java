package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for structured parsing of analysis agent responses.
 * Used by Spring AI BeanOutputConverter to enforce structured JSON output.
 */
public record IssuesResponse(List<AuditIssue> issues) {}
