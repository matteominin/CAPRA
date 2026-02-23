package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Expected feature in a SWE document, loaded from MongoDB.
 * Each feature has a checklist of criteria to verify its coverage.
 */
@Document(collection = "summary_features")
public record Feature(
        @Id String id,
        String feature,
        String description,
        String count,
        String example,
        List<String> checklist,
        Instant createdAt,
        Instant updatedAt
) {}
