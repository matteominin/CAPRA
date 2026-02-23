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
            
                        CONTEXT: this is a UNIVERSITY project. Focus ONLY on the most IMPACTFUL problems
                        that would truly make a difference in the quality of the document.
                        DO NOT report minor, pedantic, or purely formal issues.
            
                        TASK:
                        Analyze the provided document focusing on testing and architectural consistency aspects:
                        1. Identify all test cases and testing strategies described in the document
                        2. Map each test to the requirements/Use Cases it should verify
                        3. Report critical requirements that do NOT have corresponding tests (coverage gaps)
                        4. Check that declared design principles (e.g., Composition over Inheritance, SOLID)
                             are consistent with class and code descriptions
                        5. Check if tests cover alternative flows and error conditions
            
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
                        - HIGH: critical requirements (security, transactions, error handling) without tests.
                        - MEDIUM: coverage gaps on important functionalities.
                        - LOW: missing tests on secondary functionalities.
            
                        RECOMMENDATIONS:
                        The "recommendation" field MUST contain a CONCRETE and ACTIONABLE advice for the student.
                        Example: "Add a test with @Test testRechargeBalance_PaymentFailed() that verifies when payment fails, the balance is not changed and the appropriate exception is thrown."
            
                        ID FORMAT: TST-001, TST-002, etc.
            
                        CONFIDENCE SCORE:
                        The "confidenceScore" field must be a number between 0.0 and 1.0 indicating how certain you are
                        that the problem is real:
                        - 0.9-1.0: absolute certainty, unequivocal evidence in the text
                        - 0.7-0.89: good confidence, clear evidence but some ambiguity
                        - below 0.7: DO NOT report, not confident enough
            
                        DEDUPLICATION: DO NOT report the same problem already covered by the requirements agent.
                        Focus ONLY on testing gaps and architectural consistency. If a requirements problem also implies a test gap, report ONLY the test gap.
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
                            Analizza il seguente documento di Ingegneria del Software.
                            Concentrati sulla copertura dei test, la coerenza tra design e implementazione,
                            e la tracciabilità tra requisiti e test case.
                            Per ogni problema, fornisci una citazione testuale esatta dal documento.
                            
                            DOCUMENTO:
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
