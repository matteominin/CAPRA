package com.example.demo.model;

import java.util.List;

/**
 * Wrapper for structured parsing of the ConsistencyManager response.
 */
public record VerificationResponse(List<VerifiedIssue> verifiedIssues) {}
