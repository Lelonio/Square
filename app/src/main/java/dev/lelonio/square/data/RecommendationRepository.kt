package dev.lelonio.square.data

/**
 * Phase 5 discovery contract.
 *
 * Implementations may be backend shelves today or a ranked/personalised source
 * later. Home consumes only RecommendationSection and therefore does not need
 * to change when Phase 8 introduces scoring, embeddings or experiments.
 */
typealias Phase5RecommendationRepository = RecommendationRepository

typealias Phase5RecommendationSource = RecommendationSource
