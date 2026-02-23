package com.example.demo.model;

import java.util.List;

/**
 * Response produced by a single analysis agent.
 *
 * @param agentName Name of the agent that produced the analysis
 * @param issues    List of detected issues
 */
public record AgentResponse(
        String agentName,
        List<AuditIssue> issues
) {}
