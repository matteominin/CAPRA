package com.example.demo.agent;

import com.example.demo.model.GlossaryIssue;
import com.example.demo.model.GlossaryResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent that detects terminological inconsistencies in the document:
 * - Same entity called by different names (e.g. "Utente"/"User"/"Cliente")
 * - Technical terms used interchangeably (e.g. "prenotazione"/"reservation")
 * - Technical terms never defined in the glossary
 */
@Service
public class GlossaryConsistencyAgent {

    private static final Logger log = LoggerFactory.getLogger(GlossaryConsistencyAgent.class);

     private static final String SYSTEM_PROMPT = """
                You are a Software Engineering expert specialized in documentation quality.
            
                TASK:
                Analyze the document and identify TERMINOLOGICAL INCONSISTENCIES:
            
                1. UNINTENTIONAL SYNONYMS: the same entity/concept is called by different names
                    in different parts of the document.
                    Example: "User" in section 2, "Client" in section 4, "Customer" in section 6
            
                2. UNDEFINED TECHNICAL TERMS: technical words used without explanation
                    (especially if the document does NOT have a glossary)
            
                3. INCONSISTENT ACRONYMS: same acronym with different meanings, or acronyms never expanded
            
                4. MIXED LANGUAGE: inconsistent use of Italian and English terms
                    for the same concept (e.g., "prenotazione" vs "reservation")
            
                RULES:
                - Report ONLY REAL inconsistencies found in the text
                - For each group, list ALL variants used
                - severity = "MAJOR" if the inconsistency can cause real misunderstandings
                - severity = "MINOR" if it's just a matter of style
                - suggestion: indicate which term should be used uniformly and why
                - Write in ITALIAN
                - DO NOT invent inconsistencies: if the document is terminologically consistent, return an empty list
                - Maximum 10 issues: report only the most significant
                """;

    private final ChatClient chatClient;

    public GlossaryConsistencyAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the document for terminological inconsistencies.
     *
     * @param documentText full text of the document
     * @return list of glossary issues
     */
    public List<GlossaryIssue> analyze(String documentText) {
        log.info("GlossaryConsistencyAgent: terminological analysis in progress...");

        try {
            GlossaryResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento di Ingegneria del Software
                            e identifica tutte le incoerenze terminologiche.
                            
                            DOCUMENTO:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(truncate(documentText, 80000)),
                    GlossaryResponse.class, "GlossaryConsistencyAgent");

            if (response != null && response.issues() != null) {
                long major = response.issues().stream()
                        .filter(i -> "MAJOR".equalsIgnoreCase(i.severity())).count();
                log.info("GlossaryConsistencyAgent: {} inconsistencies found ({} MAJOR, {} MINOR)",
                        response.issues().size(), major, response.issues().size() - major);
                return response.issues();
            }

            log.warn("GlossaryConsistencyAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("GlossaryConsistencyAgent: error during analysis", e);
            return List.of();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... troncato ...]";
    }
}
