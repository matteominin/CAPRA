package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for JSON parsing of the glossary agent response.
 */
public record GlossaryResponse(
        List<GlossaryIssue> issues
) {}
