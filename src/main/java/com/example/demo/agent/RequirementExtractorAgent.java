package com.example.demo.agent;

import com.example.demo.model.RequirementEntry;
import com.example.demo.model.RequirementExtractorResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated agent for extracting ALL functional requirements from the document.
 * Focuses exclusively on requirement discovery — does NOT assess quality
 * and does NOT map to UCs/design/tests.
 */
@Service
public class RequirementExtractorAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractorAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a Software Engineering document analyst.

            TASK:
            Extract functional requirements from a SWE project document.
            Return JSON only, compliant with the provided schema.

            PRIORITY RULES:
            - Prefer explicit requirement IDs if present in the document (e.g., RF-1, REQ-01, R1, FR-1).
            - If explicit IDs are missing, infer requirements from obligations/behaviors in the text and assign IDs as REQ-1, REQ-2, ...
            - If explicit IDs exist and you add inferred ones, continue progressive numbering without reusing already used numbers.
            - Never use UC-* as requirement IDs.

            CONTENT RULES:
            - Extract high-value functional requirements (main goals, user-facing workflows, constraints affecting behavior).
            - Exclude purely technical implementation details (class names, DAO internals, regex specifics).
            - requirementName must be concise (max 3-5 words), user-oriented, and in the document language.
            - Produce 8 to 15 requirements when enough evidence exists in the document.
            - Do not return an empty list if the document clearly describes system behavior.
            """;

    private final ChatClient chatClient;

    public RequirementExtractorAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Extracts all requirements from the document.
     *
     * @param documentText full text of the PDF document
     * @return list of extracted requirement entries
     */
    public List<RequirementEntry> extract(String documentText) {
        log.info("RequirementExtractorAgent: extracting requirements...");

        try {
            RequirementExtractorResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Extract functional requirements from the following Software Engineering document.
                            Return JSON only using the expected schema.
                            If formal IDs are absent, infer IDs as REQ-1, REQ-2, ... .
                            Never use UC-* as requirement IDs.

                            DOCUMENT:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(documentText),
                    RequirementExtractorResponse.class, "RequirementExtractorAgent");

            if (response != null && response.requirements() != null) {
                List<RequirementEntry> functionalOnly = response.requirements().stream()
                        .filter(r -> !isLikelyTechnicalRequirement(r.requirementName()))
                        .toList();
                List<RequirementEntry> normalized = normalizeRequirementIds(functionalOnly);
                log.info("RequirementExtractorAgent: {} requirements extracted",
                        normalized.size());
                return normalized;
            }

            log.warn("RequirementExtractorAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("RequirementExtractorAgent: error during extraction", e);
            return List.of();
        }
    }

    private static final Pattern ID_WITH_NUMBER = Pattern.compile("(?i)^([A-Z]+-?)(\\d+)$");
    private static final Pattern UC_ID = Pattern.compile("(?i)^UC[- ].*");

    /**
     * Ensures requirement IDs are unique and progressive.
     * Existing valid IDs are preserved; missing/invalid/duplicate IDs become REQ-{N},
     * where N starts from max already used number + 1.
     */
    private List<RequirementEntry> normalizeRequirementIds(List<RequirementEntry> requirements) {
        if (requirements == null || requirements.isEmpty()) return List.of();

        Set<Integer> usedNumbers = new HashSet<>();
        Set<String> usedIds = new HashSet<>();

        // 1) Reserve valid existing IDs and numbers.
        for (RequirementEntry req : requirements) {
            String id = sanitize(req.requirementId());
            if (!isValidExistingId(id)) continue;
            Matcher matcher = ID_WITH_NUMBER.matcher(id.toUpperCase());
            if (!matcher.matches()) continue;
            int number = Integer.parseInt(matcher.group(2));
            if (usedIds.add(id.toUpperCase())) {
                usedNumbers.add(number);
            }
        }

        int nextNumber = usedNumbers.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<RequirementEntry> normalized = new ArrayList<>(requirements.size());
        Set<String> seenInOrder = new HashSet<>();

        // 2) Normalize each requirement, generating IDs when needed.
        for (RequirementEntry req : requirements) {
            String originalId = sanitize(req.requirementId());
            String name = sanitize(req.requirementName());

            String finalId;
            if (isValidExistingId(originalId) && !seenInOrder.contains(originalId.toUpperCase())) {
                // First occurrence of a valid ID: keep as-is.
                finalId = originalId;
                seenInOrder.add(finalId.toUpperCase());
            } else if (isValidExistingId(originalId) && seenInOrder.contains(originalId.toUpperCase())) {
                // Duplicate ID in output -> assign a fresh progressive one.
                while (usedNumbers.contains(nextNumber)) nextNumber++;
                finalId = "REQ-" + nextNumber++;
                usedIds.add(finalId.toUpperCase());
                usedNumbers.add(extractTrailingNumber(finalId));
            } else {
                // Missing/invalid ID (including UC-*) -> assign progressive ID.
                while (usedNumbers.contains(nextNumber)) nextNumber++;
                finalId = "REQ-" + nextNumber++;
                usedIds.add(finalId.toUpperCase());
                usedNumbers.add(extractTrailingNumber(finalId));
            }

            normalized.add(new RequirementEntry(
                    finalId,
                    name.isBlank() ? "Requirement " + extractTrailingNumber(finalId) : name
            ));
        }

        return normalized;
    }

    private boolean isValidExistingId(String id) {
        if (id.isBlank()) return false;
        if (UC_ID.matcher(id).matches()) return false;
        return ID_WITH_NUMBER.matcher(id.toUpperCase()).matches();
    }

    private int extractTrailingNumber(String id) {
        Matcher m = ID_WITH_NUMBER.matcher(id.toUpperCase());
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    private String sanitize(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Heuristic filter to avoid technical/internal requirements in the functional list.
     */
    private boolean isLikelyTechnicalRequirement(String requirementName) {
        String name = sanitize(requirementName).toLowerCase();
        if (name.isBlank()) return false;
        return name.contains("session")
                || name.contains("validazione")
                || name.contains("validation")
                || name.contains("regex")
                || name.contains("dao")
                || name.contains("jdbc")
                || name.contains("database connection")
                || name.contains("persistence");
    }

}
