package com.example.demo.model;

import java.util.List;

/**
 * Risposta prodotta da un singolo agente di analisi.
 *
 * @param agentName Nome dell'agente che ha prodotto l'analisi
 * @param issues    Lista di problemi rilevati
 */
public record AgentResponse(
        String agentName,
        List<AuditIssue> issues
) {}
