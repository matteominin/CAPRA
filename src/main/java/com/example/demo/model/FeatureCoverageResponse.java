package com.example.demo.model;

import java.util.List;

/**
 * Wrapper per il parsing JSON della risposta LLM sulle feature.
 */
public record FeatureCoverageResponse(
        List<FeatureCoverage> features
) {}
