package com.example.demo.repository;

import com.example.demo.model.Feature;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository per accedere alle feature attese da MongoDB (collection summary_features).
 */
public interface FeatureRepository extends MongoRepository<Feature, String> {
}
