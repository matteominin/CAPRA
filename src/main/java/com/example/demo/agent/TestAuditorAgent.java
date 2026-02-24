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
 * Test suite audit agent.
 * Analyzes the entire document to identify:
 * - Critical requirements without corresponding tests (coverage gaps)
 * - Inconsistencies between declared design principles and implementation
 * - Inadequate or missing tests for alternative flows and errors
 * - Requirements-to-test traceability issues
 */
@Service
public class TestAuditorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestAuditorAgent.class);

        private static final String SYSTEM_PROMPT = """
                        You are an ISO/IEC auditor specialized in Software Testing and Quality Assurance.
                        You are analyzing a project document (thesis/report) written by a university student.
                        Your report must be USEFUL TO THE STUDENT to improve their work.
            
                        CONTEXT: this is a UNIVERSITY academic project, NOT production software.
                        Students work alone or in small teams with limited time.
                        Focus ONLY on testing gaps with real impact on correctness or grade.
                        DO NOT report: absence of performance benchmarks, missing load tests, lack of mutation
                        testing, absence of CI pipelines, or any gap unreasonable for a student project.
            
                        TASK:
                        Analyze the provided document focusing on testing and architectural consistency aspects:
                        1. Identify all test cases and testing strategies described in the document
                        2. Map each test to the requirements/Use Cases it should verify
                        3. Report critical requirements that do NOT have corresponding tests (coverage gaps)
                        4. Check that declared design principles (e.g., Composition over Inheritance, SOLID)
                             are consistent with class and code descriptions
                        5. Check if tests cover alternative flows and error conditions
            
                        GROUPING RULE (MANDATORY):
                        If the SAME type of testing gap appears across multiple use cases or requirements,
                        DO NOT report it once per UC. Report it as A SINGLE SYSTEMIC issue.
                        Example: instead of 6 issues "UC-X has no test for error flow", produce ONE:
                        "Error flows lack test coverage systematically across UC-2, UC-4, UC-6, UC-8."
                        AIM FOR A MAXIMUM OF 6-8 TOTAL ISSUES. Prefer fewer, higher-quality, grouped observations.
            
                        ANTI-HALLUCINATION RULES (CRITICAL):
                        - The "quote" field MUST contain a VERBATIM citation from the document, copied word for word.
                            DO NOT paraphrase, DO NOT summarize. Copy exactly from the text.
                        - The "pageReference" field MUST correspond to the actual page where the quote appears.
                        - DO NOT invent tests or requirements that do not exist in the document.
                        - If you find no problems, return an empty list of issues.
            
                        CATEGORIZATION:
                        - Use "Testing" as category for coverage gaps and test issues.
                        - Use "Architecture" as category for inconsistencies between declared design and implementation.
            
                        SEVERITY:
                        - HIGH: critical requirements (security, transactions, error handling) without any tests.
                        - MEDIUM: coverage gaps on important functionalities.
                        - LOW: missing tests on secondary functionalities (use sparingly).
            
                        RECOMMENDATIONS:
                        The "recommendation" field MUST contain a CONCRETE and ACTIONABLE advice for the student.
                        Since issues are grouped, the recommendation must address the whole pattern.
                        Example: "For all transactional use cases (UC-2, UC-4, UC-6), add a test method
                        testXxx_Failure() that verifies the system correctly handles the failure case."
            
                        ID FORMAT: TST-001, TST-002, etc.
            
                        CONFIDENCE SCORE:
                        The "confidenceScore" field must be a number between 0.0 and 1.0 indicating how certain you are
                        that the problem is real:
                        - 0.9-1.0: absolute certainty, unequivocal evidence in the text
                        - 0.7-0.89: good confidence, clear evidence but some ambiguity
                        - below 0.7: DO NOT report, not confident enough
            
                        DEDUPLICATION: DO NOT report the same problem already covered by the requirements agent.
                        Focus ONLY on testing gaps and architectural consistency. If a requirements problem also implies
                        a test gap, report ONLY the test gap.
                        """;

    private final ChatClient chatClient;

    public TestAuditorAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the full document text and returns detected testing issues.
     *
     * @param documentText full text of the PDF document
     * @return agent response with the list of issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("TestAuditorAgent: starting analysis ({} characters)", documentText.length());

        try {
            IssuesResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analyze the following Software Engineering document.
                            Focus on test coverage, consistency between design and implementation,
                            and traceability between requirements and test cases.
                            For each problem, provide an exact verbatim quote from the document.
                            Write all output in ENGLISH. Do NOT translate document-specific identifiers,
                            use case names, class names, or any term exactly as it appears in the document.
                            
                            DOCUMENT:
                            ---
                            %s
                            ---
                            """.formatted(documentText),
                    IssuesResponse.class, "TestAuditorAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("TestAuditorAgent: analysis completed, {} issues found", issues.size());
            return new AgentResponse("TestAuditorAgent", issues);

        } catch (Exception e) {
            log.error("TestAuditorAgent: error during analysis", e);
            throw new RuntimeException("Error in test analysis: " + e.getMessage(), e);
        }
    }
}
