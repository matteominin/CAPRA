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
                                                You are a meta-auditor tasked with VERIFYING reports produced by other auditors.
                                                Your goal is to ELIMINATE false positives and confirm only real issues.
            
                                                You are provided with:
                                                1. The FULL TEXT of the original document
                                                2. A list of reported issues to verify
            
                                                For EACH issue you must:
                                                1. SEARCH for the quote ("quote" field) in the original document text
                                                2. Verify that the quote REALLY exists (even with minor formatting variations)
                                                3. Check that the page number is plausible
                                                4. Assess whether the report describes a REAL issue or is a false positive
                                                5. CHECK for DUPLICATE issues: two reports describing the SAME issue from the same or different perspectives. In such cases,
                                                         set verified=true ONLY for the most complete and detailed version.
            
                                                DECISIONS:
                                                - verified=true: the quote (or a very similar one) exists in the document AND the issue is real AND it is NOT a duplicate
                                                - verified=false: the quote does NOT exist, OR the issue is a false positive, OR it is a duplicate of another already confirmed issue
            
                                                RULES:
                                                - If the quote does NOT appear anywhere in the document, set verified=false
                                                - If the quote is clearly invented or too freely paraphrased, set verified=false
                                                - If the issue is a false positive (the auditor misunderstood the text), set verified=false
                                                - If the pageReference is wrong but the issue is real, CORRECT the pageReference and set verified=true
                                                - If two issues describe the same problem (e.g., "insufficient JaCoCo coverage" reported as both REQ and TST),
                                                        CONFIRM ONLY ONE (the most complete) and REJECT the other with verificationNote="Duplicate of [ID]"
                                                - ALWAYS explain the reasoning in the verificationNote field
                                                - When in doubt, be conservative: better a false negative than a false positive
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
