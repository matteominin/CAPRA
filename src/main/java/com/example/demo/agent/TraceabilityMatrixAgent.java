package com.example.demo.agent;

import com.example.demo.model.*;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent that builds the traceability matrix by mapping already-extracted
 * Use Cases and Requirements to Design and Test references.
 * <p>
 * This agent receives pre-extracted UC and Requirement lists so it can
 * focus solely on finding design/test coverage for each UC.
 */
@Service
public class TraceabilityMatrixAgent {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityMatrixAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a Software Engineering expert specialized in requirements traceability.

            TASK:
            You are given a list of USE CASES and a list of REQUIREMENTS that have
            already been extracted from the document. Your job is to build a
            TRACEABILITY MATRIX that maps:
              Requirement → Use Case → Design → Test

            For EACH USE CASE provided below:
            1. Find which REQUIREMENT(s) it implements (match by name, description, or explicit reference)
            2. Check if the document contains a design/architecture reference for this UC
               (class, controller, DAO, sequence diagram, component implementing it)
            3. Check if the document contains a test case verifying this UC

            For each REQUIREMENT that has NO associated UC, create an entry with
            useCaseId=null, useCaseName=null, hasDesign=false, hasTest=false.

            OUTPUT:
            Return one entry per row. If a requirement links to multiple UCs,
            create one entry per UC with the same requirementId.

            RULES:
            - Use EXACTLY the IDs provided (do NOT rename or re-number them)
            - requirementId: from the provided REQUIREMENTS list. null if the UC has no parent req.
            - requirementName: from the provided REQUIREMENTS list. null if no parent req.
            - useCaseId: from the provided USE CASES list. null for reqs with no UC.
            - useCaseName: from the provided USE CASES list.
            - hasDesign = true if there is at least one design/architecture reference
            - hasTest = true if there is at least one test case
            - designRef: brief description of what implements it, or "No reference found"
            - testRef: brief description of which test covers it, or "No test found"
            - gap: 1-sentence gap description. Empty string "" if fully covered.
            - Write in ENGLISH. Preserve document identifiers exactly.
            - DO NOT invent UCs or Requirements beyond the provided lists.

            CRITICAL — REQUIREMENTS vs USE CASES:
            - The REQUIREMENTS list and USE CASES list are SEPARATE inputs.
            - If the REQUIREMENTS list is empty, then ALL UCs have no parent requirement:
              set requirementId=null and requirementName=null for every UC entry.
            - Do NOT use UC-x as requirementId. Requirements have IDs like RF-1, REQ-01, R1.
            - Create ONE entry per UC. If a UC has no parent requirement, still create an entry
              with requirementId=null.
            """;

    private final ChatClient chatClient;

    public TraceabilityMatrixAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Builds the traceability matrix using pre-extracted UCs and Requirements.
     *
     * @param documentText full text of the document
     * @param useCases     extracted use cases
     * @param requirements extracted requirements
     * @return list of traceability matrix entries
     */
    public List<TraceabilityEntry> buildMatrix(String documentText,
                                                List<UseCaseEntry> useCases,
                                                List<RequirementEntry> requirements) {
        log.info("TraceabilityMatrixAgent: building traceability matrix with {} UCs and {} Reqs...",
                useCases.size(), requirements.size());

        // Build context strings for the LLM
        String ucContext = useCases.stream()
                .map(uc -> "  - %s: %s".formatted(uc.useCaseId(), uc.useCaseName()))
                .collect(Collectors.joining("\n"));

        String reqContext = requirements.stream()
                .map(r -> "  - %s: %s".formatted(r.requirementId(), r.requirementName()))
                .collect(Collectors.joining("\n"));

        try {
            TraceabilityMatrixResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Build the traceability matrix for the following document.

                            === EXTRACTED USE CASES ===
                            %s

                            === EXTRACTED REQUIREMENTS ===
                            %s

                            === DOCUMENT ===
                            %s
                            """.formatted(
                            ucContext.isEmpty() ? "(none found)" : ucContext,
                            reqContext.isEmpty() ? "(none found)" : reqContext,
                            documentText),
                    TraceabilityMatrixResponse.class, "TraceabilityMatrixAgent");

            if (response != null && response.entries() != null) {
                long withDesign = response.entries().stream().filter(TraceabilityEntry::hasDesign).count();
                long withTest = response.entries().stream().filter(TraceabilityEntry::hasTest).count();
                long gaps = response.entries().stream()
                        .filter(e -> !e.hasDesign() || !e.hasTest())
                        .count();

                log.info("TraceabilityMatrixAgent: {} entries — {}/{} with design, {}/{} with test, {} gaps",
                        response.entries().size(), withDesign, response.entries().size(),
                        withTest, response.entries().size(), gaps);
                return response.entries();
            }

            log.warn("TraceabilityMatrixAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("TraceabilityMatrixAgent: error during matrix construction", e);
            return List.of();
        }
    }
}
