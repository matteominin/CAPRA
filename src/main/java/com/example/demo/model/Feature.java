package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Feature attesa in un documento SWE, caricata da MongoDB.
 * Ogni feature ha una checklist di criteri per verificarne la copertura.
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
