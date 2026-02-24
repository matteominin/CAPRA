package com.example.demo.agent;

import com.example.demo.model.AuditIssue;
import com.example.demo.model.VerificationResponse;
import com.example.demo.model.VerifiedIssue;
import com.example.demo.service.ResilientLlmCaller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Meta-agent for verification (Verification Loop).
 * Receives outputs from RequirementsAgent and TestAuditorAgent and verifies each issue
 * by searching for evidence in the original document text.
 * <p>
 * Implements:
 * - Anti-hallucination logic (quote verification)
 * - Cross-agent deduplication
 * - Sequential ID renumbering
 */
@Service
public class ConsistencyManager {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyManager.class);

                private static final String SYSTEM_PROMPT = """
                                                You are a meta-auditor tasked with VERIFYING and CONSOLIDATING reports produced by other auditors.
                                                Your goal is to ELIMINATE false positives, confirm only real issues, and MERGE redundant ones.
                            
                                                You are provided with:
                                                1. The FULL TEXT of the original document
                                                2. A list of reported issues to verify
                            
                                                For EACH issue you must:
                                                1. SEARCH for the quote ("quote" field) in the original document text
                                                2. Verify that the quote REALLY exists (even with minor formatting variations)
                                                3. Check that the page number is plausible
                                                4. Assess whether the report describes a REAL issue or a false positive
                                                5. CHECK for DUPLICATE or SIMILAR issues that describe the same systemic pattern.
                                                         Merge them into a SINGLE issue (keep the most complete, discard the rest).
                            
                                                GROUPING RULE (MANDATORY):
                                                If multiple issues describe the SAME pattern on different use cases or requirements
                                                (e.g., REQ-003 "UC-3 lacks error flow" and REQ-007 "UC-7 lacks error flow"),
                                                MERGE them: set verified=true for the most complete one (update its description to
                                                list all affected UCs), set verified=false for all others with
                                                verificationNote="Merged into [ID]".
                                                The final report should have the fewest possible issues, each representing
                                                a distinct class of problem, not individual instances.
                            
                                                DECISIONS:
                                                - verified=true: the quote exists AND the issue is real AND it is NOT a duplicate
                                                - verified=false: quote missing, false positive, or merged into another issue
                            
                                                RULES:
                                                - If the quote does NOT appear anywhere in the document, set verified=false
                                                - If the quote is clearly invented or too freely paraphrased, set verified=false
                                                - If the issue is a false positive (the auditor misunderstood the text), set verified=false
                                                - If the pageReference is wrong but the issue is real, CORRECT it and set verified=true
                                                - ALWAYS explain the reasoning in the verificationNote field
                                                - When in doubt, be conservative: better a false negative than a false positive
                                                - CONTEXT: this is a UNIVERSITY project — do not confirm issues that are
                                                        trivially pedantic or unreasonable for an academic context
                                                """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ConsistencyManager(@Qualifier("analysisChatClient") ChatClient chatClient,
                              ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies, deduplicates, and renumbers candidate issues.
     *
     * @param documentText    full text of the document
     * @param candidateIssues issues produced by the analysis agents
     * @return list of verified, deduplicated, and renumbered issues
     */
    public List<AuditIssue> verify(String documentText, List<AuditIssue> candidateIssues) {
        if (candidateIssues.isEmpty()) {
            log.info("ConsistencyManager: no issues to verify");
            return List.of();
        }

        log.info("ConsistencyManager: starting verification of {} candidate issues", candidateIssues.size());

        try {
            String issuesJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(candidateIssues);

            VerificationResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            TESTO ORIGINALE DEL DOCUMENTO:
                            ===BEGIN DOCUMENT===
                            %s
                            ===END DOCUMENT===
                            
                            ISSUES DA VERIFICARE:
                            %s
                            
                            Per ogni issue, verifica la citazione nel testo originale sopra
                            e decidi se confermare o rigettare. ATTENZIONE: identifica e rimuovi i duplicati.
                            """.formatted(documentText, issuesJson),
                    VerificationResponse.class, "ConsistencyManager");

            if (response == null || response.verifiedIssues() == null) {
                log.warn("ConsistencyManager: null response, returning all issues unverified");
                return renumber(candidateIssues);
            }

            List<AuditIssue> verified = response.verifiedIssues().stream()
                    .filter(VerifiedIssue::verified)
                    .map(VerifiedIssue::toAuditIssue)
                    .toList();

            long rejected = response.verifiedIssues().stream()
                    .filter(vi -> !vi.verified())
                    .count();

            long duplicates = response.verifiedIssues().stream()
                    .filter(vi -> !vi.verified())
                    .filter(vi -> vi.verificationNote() != null
                            && vi.verificationNote().toLowerCase().contains("duplicat"))
                    .count();

            // Detailed verification log
            response.verifiedIssues().forEach(vi ->
                    log.debug("  {} [{}]: {} — {}",
                            vi.verified() ? "✓" : "✗",
                            vi.id(),
                            vi.verified() ? "CONFIRMED" : "REJECTED",
                            vi.verificationNote())
            );

            log.info("ConsistencyManager: verification completed — {} confirmed, {} rejected (of which {} duplicates)",
                    verified.size(), rejected, duplicates);

            // Renumber sequentially by category
            return renumber(verified);

        } catch (JsonProcessingException e) {
            log.error("ConsistencyManager: error serializing issues", e);
            return renumber(candidateIssues);
        } catch (Exception e) {
            log.error("ConsistencyManager: error during verification", e);
            log.warn("Fallback: returning all {} candidate issues without verification", candidateIssues.size());
            return renumber(candidateIssues);
        }
    }

    /**
     * Renumbers issues sequentially by prefix based on CATEGORY.
     * REQ for Requisiti, TST for Testing, ARCH for Architettura, ISS for other.
     */
    private List<AuditIssue> renumber(List<AuditIssue> issues) {
        Map<String, AtomicInteger> counters = new HashMap<>();

        // Deterministic sorting before renumbering
        List<AuditIssue> sorted = issues.stream()
                .sorted(Comparator
                        .comparing((AuditIssue i) -> i.category() != null ? i.category() : "ZZZ")
                        .thenComparing(i -> i.severity().ordinal())
                        .thenComparingInt(AuditIssue::pageReference)
                        .thenComparing(i -> i.description() != null ? i.description() : ""))
                .toList();

        List<AuditIssue> renumbered = new ArrayList<>(sorted.size());
        for (AuditIssue issue : sorted) {
            String prefix = prefixForCategory(issue.category());
            int seq = counters.computeIfAbsent(prefix, k -> new AtomicInteger(0))
                    .incrementAndGet();
            String newId = "%s-%03d".formatted(prefix, seq);
            renumbered.add(issue.withId(newId));
        }

        log.info("ConsistencyManager: renumbering completed — {}",
                counters.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue().get())
                        .collect(Collectors.joining(", ")));

        return renumbered;
    }

    /**
     * Determines the ID prefix based on the issue category.
     */
    private String prefixForCategory(String category) {
        if (category == null || category.isBlank()) return "ISS";
        return switch (category.toLowerCase()) {
            case "requisiti" -> "REQ";
            case "testing" -> "TST";
            case "architettura" -> "ARCH";
            default -> "ISS";
        };
    }


}
