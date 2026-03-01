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
            
                        CONTEXT: this is a UNIVERSITY academic project, NOT production software.
                        Students work alone or in small teams with limited time.
                        Focus ONLY on structural or logical problems with real impact on correctness or grade.
                        DO NOT report: missing Javadoc, style issues, trivial naming, minor formatting,
                        lack of logging, absence of CI/CD, missing monitoring, or anything purely cosmetic.
            
                        TASK:
                        Analyze the provided document and identify the MOST SERIOUS problems in requirements and use cases:
                        1. Check the completeness of each requirement (pre-conditions, post-conditions, alternative flows)
                        2. Identify ambiguities, contradictions, and missing requirements
                        3. Check consistency between functional and non-functional requirements
                        4. Map use cases to tests: report critical requirements without corresponding tests
                        5. Check that diagrams (if present) are consistent with textual descriptions
            
                        GROUPING RULE (MANDATORY):
                        If the SAME type of problem appears in multiple use cases or requirements (e.g., missing
                        error flow in UC-1, UC-3, UC-5), DO NOT report it once per UC.
                        Report it as A SINGLE SYSTEMIC issue that lists all affected UCs.
                        Example: instead of 5 issues "UC-X lacks error flow", produce ONE issue:
                        "Error flows are systematically missing across UC-1, UC-3, UC-5, UC-7, UC-9."
                        This applies to any recurring pattern (missing pre-conditions, missing post-conditions,
                        ambiguous actors, uncovered alternative flows, etc.).
                        AIM FOR A MAXIMUM OF 8-10 TOTAL ISSUES. Prefer fewer, higher-quality, grouped observations.
            
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
                        - MEDIUM: incomplete or ambiguous requirements with real impact.
                        - LOW: minor or formal issues (use sparingly — if it wouldn't affect the grade, skip it).
            
                        RECOMMENDATIONS:
                        The "recommendation" field MUST contain a CONCRETE and ACTIONABLE advice for the student.
                        Do not say generically "improve"; explain EXACTLY what to do.
                        Since issues are grouped, the recommendation must address the whole pattern.
                        Example: "For all transactional use cases (UC-1, UC-3, UC-5), add an alternative flow
                        describing what happens on failure: 1) the system rolls back, 2) shows an error message,
                        3) logs the failed attempt."
            
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
                        WRITE ALL OUTPUT IN ENGLISH. DO NOT translate document-specific identifiers, use case names,
                        class names, or any term exactly as it appears in the document.
                            
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
