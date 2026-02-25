package com.example.demo.agent;

import com.example.demo.model.UseCaseEntry;
import com.example.demo.model.UseCaseExtractorResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dedicated agent for extracting ALL use cases from the document.
 * Focuses exclusively on UC discovery and template extraction —
 * does NOT assess quality, does NOT map to requirements/design/tests.
 */
@Service
public class UseCaseExtractorAgent {

    private static final Logger log = LoggerFactory.getLogger(UseCaseExtractorAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a Software Engineering document analyst.

            TASK:
            Extract ALL use cases from the document. You must find EVERY use case
            mentioned anywhere: in use case diagrams, in structured templates,
            in text descriptions, in tables, in appendices.

            IMPORTANT — EXHAUSTIVE EXTRACTION:
            - Look at ALL use case diagrams carefully. Each oval/ellipse in a diagram is a UC.
            - Look for numbered use cases: UC-0, UC-1, UC-2, UC-0.1, UC-2.3, etc.
            - Look for sub-use cases (e.g., UC-2.1, UC-2.2 are sub-UCs of UC-2)
            - Look for <<include>> and <<extend>> relationships — both sides are UCs
            - Look for structured UC templates (tables or forms with Actor, Preconditions,
              Main Flow, Postconditions, Alternative Flows)
            - Look for UCs mentioned only in text (e.g., "Use Case 5: Manage Loans")
            - Include ALL UCs regardless of detail level

            FOR EACH USE CASE, provide:
            - useCaseId: EXACTLY as written in the document (e.g., "UC-1", "UC-0.1", "UC 5")
            - useCaseName: the name/title (e.g., "Registrazione", "Gestione Prestiti")
            - hasTemplate: true ONLY if the document contains a STRUCTURED TEMPLATE
              (with fields like Actor, Preconditions, Main Flow, Postconditions).
              A simple text mention does NOT count as a template.
            - actor: the primary actor from the template. null if no template.
            - preconditions: from the template. null if no template.
            - mainFlow: the main flow steps from the template. null if no template.
            - postconditions: from the template. null if no template.
            - alternativeFlows: alternative/exception flows from the template. null if none.

            DEDUPLICATION RULES:
            - Do NOT create duplicate entries for the same use case
            - If a UC appears both in a diagram (as an oval) AND in a structured template,
              create ONLY ONE entry — the one with hasTemplate=true
            - If a diagram oval label (e.g., "registrazione") matches a numbered UC
              (e.g., UC-1 "Registrazione"), keep ONLY the numbered version (UC-1)
            - Do NOT list diagram labels as separate UCs if they are the same as numbered UCs

            RULES:
            - Extract ALL UCs — do not skip any
            - Do NOT invent UCs that are not in the document
            - Preserve the EXACT ID format from the document
            - Preserve the EXACT name as written (do not translate)
            - If a UC appears in a diagram but has no template, include it with hasTemplate=false
            - Write field values in the SAME LANGUAGE as the document (usually Italian)
            - Each use case should appear EXACTLY ONCE in the output
            """;

    private final ChatClient chatClient;

    public UseCaseExtractorAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Extracts all use cases from the document.
     *
     * @param documentText full text of the PDF document
     * @return list of extracted use case entries
     */
    public List<UseCaseEntry> extract(String documentText) {
        log.info("UseCaseExtractorAgent: extracting use cases...");

        try {
            UseCaseExtractorResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Extract ALL use cases from the following Software Engineering document.
                            Be EXHAUSTIVE: check diagrams, templates, tables, text, appendices.
                            Do NOT skip sub-use-cases (e.g., UC-2.1, UC-0.1).
                            Preserve original IDs and names exactly as written.

                            DOCUMENT:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(documentText),
                    UseCaseExtractorResponse.class, "UseCaseExtractorAgent");

            if (response != null && response.useCases() != null) {
                long withTemplate = response.useCases().stream().filter(UseCaseEntry::hasTemplate).count();
                log.info("UseCaseExtractorAgent: {} UCs extracted ({} with template, {} without)",
                        response.useCases().size(), withTemplate,
                        response.useCases().size() - withTemplate);
                return response.useCases();
            }

            log.warn("UseCaseExtractorAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("UseCaseExtractorAgent: error during extraction", e);
            return List.of();
        }
    }
}
