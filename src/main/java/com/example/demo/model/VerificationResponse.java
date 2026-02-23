package com.example.demo.model;

import java.util.List;

/**
 * Wrapper per il parsing strutturato della risposta del ConsistencyManager.
 */
public record VerificationResponse(List<VerifiedIssue> verifiedIssues) {}
