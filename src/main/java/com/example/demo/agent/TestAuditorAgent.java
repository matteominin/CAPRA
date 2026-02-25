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
                        Students work alone or in small teams with limited time and resources.
                        The professor evaluates whether the student demonstrates understanding of testing
                        methodology — NOT whether every single edge case is covered.
            
                        LENIENCY CALIBRATION (CRITICAL):
                        - If the student has unit tests, integration tests, OR functional tests, that is GOOD.
                        - Do NOT complain about missing test types that are unreasonable for the project scope
                          (no load tests, no mutation testing, no E2E tests unless they are trivial to add).
                        - If test coverage is described and appears reasonable (e.g., >60%), that is SUFFICIENT.
                        - Do NOT report every single UC without a dedicated test — systemic grouping only.
                        - Do NOT report missing negative tests unless the functionality is critical (security, payments).
                        - A well-tested student project deserves praise, not a long list of gaps.
                        - It is PERFECTLY FINE to return 0-2 issues if testing is well done.
                        - An empty issue list is a valid and welcome result.
            
                        DO NOT REPORT:
                        - Absence of performance benchmarks, load tests, mutation testing
                        - Absence of CI pipelines or automated deployment testing
                        - Minor coverage gaps on secondary/optional features
                        - Missing controller-level tests if service/DAO tests exist
                        - Any gap unreasonable for a student project
            
                        TASK:
                        Analyze the provided document focusing ONLY on SIGNIFICANT testing problems:
                        1. Are there any critical requirements completely untested?
                        2. Is someone claiming high coverage but with obvious gaps?
                        3. Are there major inconsistencies between the testing strategy and implementation?
            
                        GROUPING RULE (MANDATORY):
                        If the SAME type of testing gap appears across multiple use cases,
                        report it as A SINGLE SYSTEMIC issue.
                        AIM FOR A MAXIMUM OF 4-5 TOTAL ISSUES. Prefer fewer, higher-quality observations.
            
                        === ISSUE FIELDS ===
                        For each issue, provide ALL of the following fields:
                        - id: format TST-001, TST-002, etc.
                        - severity: HIGH / MEDIUM / LOW
                        - shortDescription: ONE SENTENCE (max 15 words) summarizing the problem
                        - description: detailed explanation of the testing problem
                        - pageReference: page number where the problem appears
                        - quote: VERBATIM citation from the document (copy exactly, do NOT paraphrase)
                        - category: "Testing" for coverage gaps, "Architecture" for design inconsistencies
                        - recommendation: CONCRETE and ACTIONABLE advice (explain EXACTLY what to do).
                            MAXIMUM 80 WORDS. Be concise: state the action, not the context.
                        - confidenceScore: 0.0-1.0 (only report if >= 0.8)

                        ANTI-HALLUCINATION RULES (CRITICAL):
                        - The "quote" field MUST contain a VERBATIM citation from the document.
                        - DO NOT invent tests or requirements that do not exist in the document.
                        - If testing is well done, return an EMPTY list. Do NOT force issues.

                        SEVERITY:
                        - HIGH: critical requirements (security, data integrity) completely untested.
                        - MEDIUM: significant, clearly documented functionality without any test.
                        - LOW: DO NOT USE unless truly worth noting. When in doubt, skip it.

                        DEDUPLICATION: DO NOT report the same problem already covered by the requirements agent.
            
                        OUTPUT FORMAT: Use a formal, professional, and objective tone throughout.
                        Write everything in ENGLISH.
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
