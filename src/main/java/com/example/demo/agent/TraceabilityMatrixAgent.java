package com.example.demo.agent;

import com.example.demo.model.TraceabilityEntry;
import com.example.demo.model.TraceabilityMatrixResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent that automatically builds a traceability matrix
 * Requirements/UC → Design → Test, highlighting coverage gaps.
 * <p>
 * This is one of the most important checks in SWE: students
 * almost never produce a complete matrix.
 */
@Service
public class TraceabilityMatrixAgent {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityMatrixAgent.class);

        private static final String SYSTEM_PROMPT = """
                        You are a Software Engineering expert specialized in requirements traceability.
            
                        TASK:
                        Analyze the document and build a TRACEABILITY MATRIX that maps:
                        Requirement → Use Case → Design (classes, components, patterns) → Test Case
            
                        For each Use Case present in the document:
                        1. Identify its ID and name (e.g., UC-1 - User Registration)
                        2. Identify the PARENT REQUIREMENT that this UC implements (e.g., RF-1, REQ-1, R1).
                           Use the requirement ID and name as they appear in the document.
                           If the UC has no parent requirement, set requirementId and requirementName to null.
                        3. Check if the document contains a related design/architecture description
                             (e.g., class, controller, DAO, sequence diagram implementing it)
                        4. Check if the document contains a test case verifying that use case
                        5. If design OR test is missing, report the gap
            
                        ALSO: if a REQUIREMENT exists in the document but has NO associated Use Case,
                        create an entry with the requirement's ID and name, useCaseId=null, useCaseName=null,
                        hasDesign=false, hasTest=false, and gap describing the missing UC.
            
                        RULES:
                        - List ALL Use Cases AND all requirements found in the document
                        - requirementId: the ID of the parent requirement (e.g., RF-1, REQ-1). null if none.
                        - requirementName: the name of the parent requirement. null if none.
                        - hasDesign = true if there is at least one architectural reference (class, controller, DAO, diagram)
                        - hasTest = true if there is at least one test case covering it
                        - designRef: briefly describe WHAT implements it (e.g., "ReservationController, DAO pattern")
                            If not found, write "No reference found"
                        - testRef: briefly describe WHICH test covers it (e.g., "testReservation_Success")
                            If not found, write "No test found"
                        - gap: describe in 1 sentence the main gap. If all covered, EMPTY string "".
                        - Write in ENGLISH. Do NOT translate document-specific use case names,
                          identifiers, class names, or any term exactly as it appears in the document.
                        - DO NOT invent Use Cases or Requirements that do not exist in the document
                        """;

    private final ChatClient chatClient;

    public TraceabilityMatrixAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the document and produces the traceability matrix.
     *
     * @param documentText full text of the document
     * @return list of traceability matrix entries
     */
    public List<TraceabilityEntry> buildMatrix(String documentText) {
        log.info("TraceabilityMatrixAgent: building traceability matrix...");

        try {
            TraceabilityMatrixResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analyze the following Software Engineering document and build
                            a complete traceability matrix UC/Requirements -> Design -> Test.
                            Write all output in ENGLISH. Do NOT translate document-specific identifiers,
                            use case names, or any term exactly as it appears in the document.
                            
                            DOCUMENT:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(documentText),
                    TraceabilityMatrixResponse.class, "TraceabilityMatrixAgent");

            if (response != null && response.entries() != null) {
                long withDesign = response.entries().stream().filter(TraceabilityEntry::hasDesign).count();
                long withTest = response.entries().stream().filter(TraceabilityEntry::hasTest).count();
                long gaps = response.entries().stream()
                        .filter(e -> !e.hasDesign() || !e.hasTest())
                        .count();

                log.info("TraceabilityMatrixAgent: {} UC/requisiti trovati — {}/{} con design, {}/{} con test, {} gap",
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
