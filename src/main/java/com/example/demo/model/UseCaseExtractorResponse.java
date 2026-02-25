package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for JSON parsing of use case extraction response.
 */
public record UseCaseExtractorResponse(
        List<UseCaseEntry> useCases
) {}
