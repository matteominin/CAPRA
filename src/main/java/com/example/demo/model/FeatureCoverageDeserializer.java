package com.example.demo.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Custom deserializer for FeatureCoverage.
 * Handles duplicate JSON keys produced by LLM output.
 */
public class FeatureCoverageDeserializer extends JsonDeserializer<FeatureCoverage> {

    @Override
    public FeatureCoverage deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);

        String featureName = text(node, "featureName");
        String category = text(node, "category");
        FeatureCoverage.FeatureStatus status = parseStatus(text(node, "status"));
        int coverageScore = intValue(node, "coverageScore");
        String evidence = text(node, "evidence");
        int matchedItems = intValue(node, "matchedItems");
        int totalItems = intValue(node, "totalItems");

        return new FeatureCoverage(
                featureName,
                category,
                status,
                coverageScore,
                evidence,
                matchedItems,
                totalItems
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private static int intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    private static FeatureCoverage.FeatureStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return FeatureCoverage.FeatureStatus.ABSENT;
        }
        try {
            return FeatureCoverage.FeatureStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FeatureCoverage.FeatureStatus.ABSENT;
        }
    }
}
