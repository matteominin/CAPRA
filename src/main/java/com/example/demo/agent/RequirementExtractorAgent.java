package com.example.demo.agent;

import com.example.demo.model.RequirementEntry;
import com.example.demo.model.RequirementExtractorResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dedicated agent for extracting ALL functional requirements from the document.
 * Focuses exclusively on requirement discovery — does NOT assess quality
 * and does NOT map to UCs/design/tests.
 */
@Service
public class RequirementExtractorAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractorAgent.class);

        private static final String SYSTEM_PROMPT = """
            You are a Software Engineering document analyst.

            TASK:
            Extract ONLY the most important, high-level functional requirements that define the main goals, features, and user-facing behaviors of the system.
            Requirements are typically listed in a dedicated section (e.g.,
            "Requisiti Funzionali", "Functional Requirements", "Requisiti",
            "Specifica dei Requisiti", etc.) and have IDs like RF-1, RF-2,
            REQ-01, R1, FR-1, etc.

            CRITICAL — PRIORITIZE CORE REQUIREMENTS:
            - Extract ONLY the most important, high-level requirements essential for the system's purpose, main workflows, and user value.
            - Ignore or omit low-level, technical, or implementation-specific requirements (e.g., DAO class usage, regex validation, error alert details, internal class names).
            - Focus on requirements that are essential for the system’s main features and user-facing behaviors.
            - Limit the output to the 10–15 most critical requirements. If more exist, select those with the highest impact on system functionality.
            - For each, provide a concise requirementId (REQ-1, REQ-2, ...) and a clear, user-oriented requirementName.

            REQUIREMENT NAME:
            - The requirementName must be a short summary (max 3–5 words), not a long sentence.
            - Use only the most essential keywords (e.g., "User registration", "Book loan management", "Comment system").
            - If the document uses Italian, keep the name in Italian, but keep it short.

            INFERRED REQUIREMENTS:
            - If the document does NOT contain a formal requirements section, you MUST infer requirements from the text.
            - Extract requirements from any part of the document where system obligations, constraints, or expected behaviors are described (e.g., "The system must...", "Users can...", "It is required that...").
            - Assign a unique requirementId for each inferred requirement, using the format REQ-1, REQ-2, REQ-3, etc.
            - Do NOT use UC-x, UC-1, UC-2, etc. as requirement IDs.

            TABLE FORMAT:
            - Output the requirements in a table with wider columns for readability.
            - Columns: requirementId | requirementName | (optionally: linkedUseCases)
            - Make sure the table is easy to read and not cramped.

            RULES:
            - Extract ONLY the most important requirements — do not include minor or technical details
            - Assign REQ-x IDs in order of importance or appearance
            - Do NOT generate IDs like "REQ-login" or "REQ-registrazione"
            - Do NOT use UC-x, UC-1, UC-2, etc. as requirement IDs
            - If the document uses Italian, keep the name in Italian (max 3–5 words)
            - If no explicit requirements section or IDs exist, infer requirements from the text
            """;

    private final ChatClient chatClient;

    public RequirementExtractorAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Extracts all requirements from the document.
     *
     * @param documentText full text of the PDF document
     * @return list of extracted requirement entries
     */
    public List<RequirementEntry> extract(String documentText) {
        log.info("RequirementExtractorAgent: extracting requirements...");

        try {
            RequirementExtractorResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Extract ALL functional requirements from the following Software Engineering document.
                            Be EXHAUSTIVE: check all sections, tables, appendices.
                            Use the EXACT requirement IDs from the document (e.g., RF-1, not REQ-login).

                            DOCUMENT:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(documentText),
                    RequirementExtractorResponse.class, "RequirementExtractorAgent");

            if (response != null && response.requirements() != null) {
                log.info("RequirementExtractorAgent: {} requirements extracted",
                        response.requirements().size());
                return response.requirements();
            }

            log.warn("RequirementExtractorAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("RequirementExtractorAgent: error during extraction", e);
            return List.of();
        }
    }
}
