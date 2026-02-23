package com.example.demo.agent;

import com.example.demo.model.AgentResponse;
import com.example.demo.model.AuditIssue;
import com.example.demo.model.IssuesResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Requirements and use-case analysis agent.
 * Analyzes the entire document to identify:
 * - Incomplete or ambiguous requirements
 * - Contradictions in business logic
 * - Use Cases without test coverage
 * - Inconsistencies between functional and non-functional requirements
 */
@Service
public class RequirementsAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAgent.class);

        private static final String SYSTEM_PROMPT = """
                        You are an ISO/IEC 25010 auditor specialized in Software Engineering.
                        You are analyzing a project document (thesis/report) written by a university student.
                        Your report must be USEFUL TO THE STUDENT to improve their work.
            
                        CONTEXT: this is a UNIVERSITY project. Focus ONLY on the most IMPACTFUL problems
                        that would truly make a difference in the quality of the document.
                        DO NOT report minor, pedantic, or purely formal issues.
            
                        TASK:
                        Analyze the provided document and identify the MOST SERIOUS problems in requirements and use cases:
                        1. Check the completeness of each requirement (pre-conditions, post-conditions, alternative flows)
                        2. Identify ambiguities, contradictions, and missing requirements
                        3. Check consistency between functional and non-functional requirements
                        4. Map use cases to tests: report critical requirements without corresponding tests
                        5. Check that diagrams (if present) are consistent with textual descriptions
            
                        ANTI-HALLUCINATION RULES (CRITICAL):
                        - The "quote" field MUST contain a VERBATIM citation from the document, copied word for word.
                            DO NOT paraphrase, DO NOT summarize. Copy exactly from the text.
                        - The "pageReference" field MUST correspond to the actual page where the quote appears.
                            If unsure about the page, use the best approximation but DO NOT invent.
                        - DO NOT invent problems that do not exist in the text.
                        - If you find no problems, return an empty list of issues.
            
                        CATEGORIZATION:
                        - Use "Requirements" as category for issues about requirements and use cases.
                        - Use "Architecture" as category for architectural or design issues.
            
                        SEVERITY:
                        - HIGH: security, transactions, and error handling issues without coverage.
                        - MEDIUM: incomplete or ambiguous requirements.
                        - LOW: minor or formal issues.
            
                        RECOMMENDATIONS:
                        The "recommendation" field MUST contain a CONCRETE and ACTIONABLE advice for the student.
                        Do not say generically "improve"; explain EXACTLY what to do.
                        Example: "Add to alternative flow 3.1 the specific handling steps: 1) the system shows an error,
                        2) the user is redirected to the previous page, 3) the system logs the failed attempt."
            
                        ID FORMAT: REQ-001, REQ-002, etc.
            
                        CONFIDENCE SCORE:
                        The "confidenceScore" field must be a number between 0.0 and 1.0 indicating how certain you are
                        that the problem is real:
                        - 0.9-1.0: absolute certainty, unequivocal evidence in the text
                        - 0.7-0.89: good confidence, clear evidence but some ambiguity
                        - below 0.7: DO NOT report, not confident enough
            
                        DEDUPLICATION: DO NOT report the same problem from different perspectives. If a problem
                        concerns both requirements and tests, report it ONLY ONCE in the most relevant category.
                        """;

    private final ChatClient chatClient;

    public RequirementsAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the full document text and returns detected requirements issues.
     *
     * @param documentText full text of the PDF document
     * @return agent response with the list of issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("RequirementsAgent: starting analysis ({} characters)", documentText.length());

        try {
                IssuesResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                        Analyze the following Software Engineering document.
                        Identify all problems related to requirements, use cases, and business logic.
                        For each problem, provide an exact verbatim quote from the document.
                            
                        DOCUMENT:
                        ---
                        %s
                        ---
                        """.formatted(documentText),
                    IssuesResponse.class, "RequirementsAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("RequirementsAgent: analysis completed, {} issues found", issues.size());
            return new AgentResponse("RequirementsAgent", issues);

        } catch (Exception e) {
            log.error("RequirementsAgent: error during analysis", e);
            throw new RuntimeException("Error in requirements analysis: " + e.getMessage(), e);
        }
    }
}
