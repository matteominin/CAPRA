package com.example.demo.agent;

import com.example.demo.model.Feature;
import com.example.demo.model.FeatureCoverage;
import com.example.demo.model.FeatureCoverageResponse;
import com.example.demo.service.ResilientLlmCaller;
import com.example.demo.repository.FeatureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent that verifies the presence of expected features (from MongoDB) in the analyzed document.
 * Loads features from the summary_features collection and uses the LLM to check
 * which checklist items are satisfied in the document text.
 */
@Service
public class FeatureCheckAgent {

    private static final Logger log = LoggerFactory.getLogger(FeatureCheckAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a Software Engineering expert. You are provided with the text of a SWE project document (thesis/report) and a list of EXPECTED FEATURES, each with a checklist.
            
            TASK:
            For each feature, verify if and to what extent it is present in the document by analyzing the checklist.
            
            For each checklist item:
            - Look for concrete evidence in the document text
            - Mark the item as satisfied ONLY if you find clear evidence
            
            RULES:
            - status = "PRESENT" if at least 80%% of checklist items are satisfied
            - status = "PARTIAL" if between 30%% and 79%% of checklist items are satisfied
            - status = "ABSENT" if less than 30%% of checklist items are satisfied
            - coverageScore = percentage of satisfied items (0-100, integer)
            - matchedItems = number of satisfied items
            - totalItems = total number of items in the checklist
            - evidence = one or two sentences explaining what you found (or what is missing). NO LaTeX commands.
            - DO NOT invent evidence. If you don't find something, state it clearly.
            - Write everything in ENGLISH. Do NOT translate document-specific feature names, use case
              identifiers, class names, or any term exactly as it appears in the analyzed document.
            """;

    private final ChatClient chatClient;
    private final FeatureRepository featureRepository;

    public FeatureCheckAgent(@Qualifier("analysisChatClient") ChatClient chatClient,
                             FeatureRepository featureRepository) {
        this.chatClient = chatClient;
        this.featureRepository = featureRepository;
    }

    /**
     * Loads features from MongoDB and verifies their presence in the document.
     *
     * @param documentText full text of the PDF document
     * @return coverage list for each feature, or empty list if no features in DB
     */
    public List<FeatureCoverage> checkFeatures(String documentText) {
        List<Feature> features = featureRepository.findAll();

        if (features.isEmpty()) {
            log.warn("FeatureCheckAgent: no features found in MongoDB, skipping check");
            return List.of();
        }

        log.info("FeatureCheckAgent: verifying {} features from the database", features.size());

        // Build the feature description for the prompt
        String featuresDescription = features.stream()
                .map(f -> {
                    String checklist = (f.checklist() != null && !f.checklist().isEmpty())
                            ? f.checklist().stream()
                                .map(item -> "    - " + item)
                                .collect(Collectors.joining("\n"))
                            : "    (no checklist)";
                    return "FEATURE: %s\nDescrizione: %s\nChecklist:\n%s".formatted(
                            f.feature(), f.description(), checklist);
                })
                .collect(Collectors.joining("\n\n"));

        try {
            FeatureCoverageResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analyze the following document and verify the presence of each feature.
                            Write all output in ENGLISH. Do NOT translate document-specific feature names,
                            use case identifiers, or any term exactly as it appears in the document.
                            
                            DOCUMENT:
                            ===BEGIN===
                            %s
                            ===END===
                            
                            FEATURES TO VERIFY:
                            %s
                            
                            For each feature, return featureName (exactly as provided),
                            status, coverageScore, evidence, matchedItems and totalItems.
                            For the "category" field use one of: Requirements, Architecture, Testing, Design, Documentation.
                            """.formatted(documentText, featuresDescription),
                    FeatureCoverageResponse.class, "FeatureCheckAgent");

            if (response != null && response.features() != null) {
                long present = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
                long partial = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
                long absent = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();

                log.info("FeatureCheckAgent: result — {} present, {} partial, {} absent",
                        present, partial, absent);
                return response.features();
            }

            log.warn("FeatureCheckAgent: null response from LLM");
            return List.of();

        } catch (Exception e) {
            log.error("FeatureCheckAgent: error during feature check", e);
            return List.of();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... truncated ...]";
    }
}
