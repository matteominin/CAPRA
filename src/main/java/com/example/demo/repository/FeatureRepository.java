package com.example.demo.repository;

import com.example.demo.model.Feature;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for accessing expected features from MongoDB (collection summary_features).
 */
public interface FeatureRepository extends MongoRepository<Feature, String> {
}
