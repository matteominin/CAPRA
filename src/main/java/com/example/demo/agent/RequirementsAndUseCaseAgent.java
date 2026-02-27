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
public class RequirementsAndUseCaseAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAndUseCaseAgent.class);

        private static final String SYSTEM_PROMPT = """
                        You are an ISO/IEC 25010 auditor specialized in Software Engineering.
                        You are analyzing a project document (thesis/report) written by a university student.
                        Your report must be USEFUL TO THE STUDENT to improve their work.
            
                        CONTEXT: this is a UNIVERSITY academic project, NOT production software.
                        Students work alone or in small teams with limited time and resources.
                        The professor evaluates whether the student demonstrates understanding of SWE
                        methodology — NOT whether the document is production-ready or ISO-compliant.
            
                        LENIENCY CALIBRATION (CRITICAL):
                        - A well-structured document with minor imperfections is a GOOD document.
                        - DO NOT report issues that the professor would consider acceptable for a student project.
                        - If a section is present and reasonably well-done, DO NOT nitpick.
                        - If use cases follow a recognizable template and cover the main flows, that is SUFFICIENT
                          even if some alternative flows are missing.
                        - If the document has tests and reasonable coverage, do NOT complain about edge cases.
                        - Only report problems that would genuinely make the professor lower the grade.
                        - It is PERFECTLY FINE to return 0-3 issues if the document is good.
                        - An empty issue list is a valid and welcome result for a strong document.
            
                        DO NOT REPORT:
                        - Missing Javadoc, style issues, trivial naming, minor formatting
                        - Lack of CI/CD, monitoring, logging, deployment considerations
                        - Missing NFRs if the project scope does not require them
                        - Incomplete alternative flows if the main flows are well-described
                        - Minor inconsistencies between diagrams and text that do not affect understanding
                        - Anything purely cosmetic or that only an ISO auditor would notice
            
                        TASK:
                        Analyze the provided document and identify ONLY the MOST SERIOUS problems
                        (those that would actually lower the grade) in requirements and use cases.
            
                        === EXTRACTION COMPLETENESS CHECKLIST ===
                        Use this checklist to evaluate the document, but DO NOT REPORT items as issues
                        unless they represent a SIGNIFICANT gap. A partial response is acceptable.
                        
                        1. REQUIREMENTS COMPLETENESS:
                           - Are functional requirements clearly stated?
                           - Are the main flows described?
                        
                        2. BUSINESS LOGIC / USE-CASE COMPLETENESS:
                           - Do UCs follow a recognizable template?
                           - Are the main actors identified?
                           - Is the core business logic captured?
                        
                        3. NON-FUNCTIONAL REQUIREMENTS:
                           - Are the most relevant NFRs mentioned (even briefly)?
                        
                        4. DIAGRAMS & CONSISTENCY:
                           - Are UML diagrams present?
                           - Are there major inconsistencies between diagrams and text?
            
                        === ISSUE FIELDS ===
                        For each issue, provide ALL of the following fields:
                        - id: format REQ-001, REQ-002, etc.
                        - severity: HIGH / MEDIUM / LOW
                        - shortDescription: ONE SENTENCE (max 15 words) summarizing the problem
                        - description: detailed explanation of the problem
                        - pageReference: page number where the problem appears
                        - quote: VERBATIM citation from the document (copy exactly, do NOT paraphrase)
                        - category: "Requirements" for requirements/UC issues, "Architecture" for design issues
                        - recommendation: CONCRETE and ACTIONABLE advice (explain EXACTLY what to do).
                            MAXIMUM 80 WORDS. Be concise: state the action, not the context.
                        - confidenceScore: 0.0-1.0 (only report if >= 0.8)
            
                        GROUPING RULE (MANDATORY):
                        If the SAME type of problem appears in multiple use cases, report it as A SINGLE
                        SYSTEMIC issue. AIM FOR A MAXIMUM OF 5-6 TOTAL ISSUES.
                        Prefer fewer, higher-quality observations over many minor ones.
            
                        ANTI-HALLUCINATION RULES (CRITICAL):
                        - The "quote" field MUST contain a VERBATIM citation from the document.
                        - DO NOT invent problems that do not exist in the text.
                        - If you find no significant problems, return an EMPTY list of issues.
                        - DO NOT force issues: a good document deserves an empty or near-empty report.
            
                        SEVERITY:
                        - HIGH: fundamental gaps that compromise the document's purpose (missing entire
                          sections, critical contradictions, completely missing requirements).
                        - MEDIUM: significant omissions that a professor would likely deduct points for.
                        - LOW: DO NOT USE unless the issue is genuinely worth noting. When in doubt, skip it.
            
                        DEDUPLICATION: DO NOT report the same problem from different perspectives.
            
                        OUTPUT FORMAT: Use a formal, professional, and objective tone throughout.
                        Write everything in ENGLISH.
                        """;

    private final ChatClient chatClient;

    public RequirementsAndUseCaseAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the full document text and returns detected requirements issues.
     *
     * @param documentText full text of the PDF document
     * @return agent response with the list of issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("RequirementsAndUseCaseAgent: starting analysis ({} characters)", documentText.length());

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
                    IssuesResponse.class, "RequirementsAndUseCaseAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("RequirementsAndUseCaseAgent: analysis completed, {} issues found", issues.size());
            return new AgentResponse("RequirementsAndUseCaseAgent", issues);

        } catch (Exception e) {
            log.error("RequirementsAndUseCaseAgent: error during analysis", e);
            throw new RuntimeException("Error in requirements analysis: " + e.getMessage(), e);
        }
    }
}
