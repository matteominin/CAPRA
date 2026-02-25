package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for JSON parsing of requirement extraction response.
 */
public record RequirementExtractorResponse(
        List<RequirementEntry> requirements
) {}
