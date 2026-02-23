package com.example.demo.model;

import java.util.List;

/**
 * Wrapper per il parsing JSON della risposta dell'agente glossario.
 */
public record GlossaryResponse(
        List<GlossaryIssue> issues
) {}
