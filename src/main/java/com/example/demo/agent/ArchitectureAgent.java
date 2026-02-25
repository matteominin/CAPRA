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
 * Architecture analysis agent.
 * Analyzes the document to identify architectural components, patterns,
 * and common design flaws such as tight coupling, missing separation of
 * concerns, and scalability/resilience gaps.
 */
@Service
public class ArchitectureAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are an ISO/IEC 25010 auditor specialized in Software Architecture.
            You are analyzing a project document (thesis/report) written by a university student.
            Your report must be USEFUL TO THE STUDENT to improve their work.

            CONTEXT: this is a UNIVERSITY academic project, NOT production software.
            Students work alone or in small teams with limited time and resources.
            The professor evaluates whether the student demonstrates understanding of
            architectural concepts — NOT whether the architecture is production-grade.

            LENIENCY CALIBRATION (CRITICAL):
            - If the student identifies layers (presentation, logic, data) and describes
              their interactions, that is GOOD even if the separation is not perfect.
            - If at least one design pattern is used and reasonably well applied, that is GOOD.
            - Do NOT report tight coupling unless it makes the system clearly untestable or broken.
            - Do NOT report SOLID violations unless they are egregious and clearly documented.
            - Do NOT report missing abstraction layers if the project is small and simple.
            - A reasonable MVC/layered architecture with some imperfections is ACCEPTABLE.
            - It is PERFECTLY FINE to return 0-2 issues if the architecture is reasonable.
            - An empty issue list is a valid and welcome result.

            DO NOT REPORT:
            - Missing cloud deployment, microservices, load balancing, containerization
            - Minor pattern misapplications that do not affect functionality
            - Incomplete class diagrams if the main components are documented
            - Lack of sequence diagrams if the architecture is otherwise clear
            - Any infrastructure concern unreasonable for a university project

            TASK:
            Analyze the provided document focusing ONLY on SIGNIFICANT architectural issues:
            1. Is the architecture clearly described with identifiable layers?
            2. Are there major design flaws that compromise correctness?
            3. Are there critical inconsistencies between architecture description and implementation?

            === ISSUE FIELDS ===
            For each issue, provide ALL of the following fields:
            - id: format ARCH-001, ARCH-002, etc.
            - severity: HIGH / MEDIUM / LOW
            - shortDescription: ONE SENTENCE (max 15 words) summarizing the problem
            - description: detailed explanation of the architectural problem
            - pageReference: page number where the problem appears
            - quote: VERBATIM citation from the document (copy exactly, do NOT paraphrase)
            - category: always "Architecture"
            - recommendation: CONCRETE and ACTIONABLE advice (explain EXACTLY what to change).
                MAXIMUM 80 WORDS. Be concise: state the action, not the context.
            - confidenceScore: 0.0-1.0 (only report if >= 0.8)

            GROUPING RULE (MANDATORY):
            If the SAME type of problem appears across multiple components,
            report it as A SINGLE SYSTEMIC issue listing all affected components.
            AIM FOR A MAXIMUM OF 3-4 TOTAL ISSUES.

            ANTI-HALLUCINATION RULES (CRITICAL):
            - The "quote" field MUST be a VERBATIM citation from the document.
            - DO NOT invent architectural problems that do not exist in the text.
            - If the architecture is reasonable, return an EMPTY list. Do NOT force issues.

            SEVERITY:
            - HIGH: fundamental design flaws that compromise correctness or make the system unworkable.
            - MEDIUM: significant architectural gaps that the professor would likely deduct points for.
            - LOW: DO NOT USE unless truly worth noting. When in doubt, skip it.

            OUTPUT FORMAT: Use a formal, professional, and objective tone throughout.
            Write everything in ENGLISH.
            """;

    private final ChatClient chatClient;

    public ArchitectureAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analyzes the full document text and returns detected architecture issues.
     *
     * @param documentText full text of the PDF document
     * @return agent response with the list of architecture issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("ArchitectureAgent: starting analysis ({} characters)", documentText.length());

        try {
            IssuesResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                        Analyze the following Software Engineering document.
                        Focus on architectural structure, design patterns, component separation,
                        and design flaws.
                        For each problem, provide an exact verbatim quote from the document.
                        Write all output in ENGLISH.
                            
                        DOCUMENT:
                        ---
                        %s
                        ---
                        """.formatted(documentText),
                    IssuesResponse.class, "ArchitectureAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("ArchitectureAgent: analysis completed, {} issues found", issues.size());
            return new AgentResponse("ArchitectureAgent", issues);

        } catch (Exception e) {
            log.error("ArchitectureAgent: error during analysis", e);
            throw new RuntimeException("Error in architecture analysis: " + e.getMessage(), e);
        }
    }
}
