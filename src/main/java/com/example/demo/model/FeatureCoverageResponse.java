package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for JSON parsing of the LLM feature response.
 */
public record FeatureCoverageResponse(
        List<FeatureCoverage> features
) {}
