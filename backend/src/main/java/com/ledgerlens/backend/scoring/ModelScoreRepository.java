package com.ledgerlens.backend.scoring;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ModelScoreRepository extends JpaRepository<ModelScore, Long> {

    // The upsert lookup: does a verdict already exist for this subject from
    // this model version? Matches the UNIQUE constraint in my schema exactly,
    // which is what makes re-scoring idempotent.
    Optional<ModelScore> findBySubjectTypeAndSubjectIdAndModelName(
            String subjectType, Long subjectId, String modelName);

    // THE FALLBACK QUERY — the reason my API survives the scoring service being
    // down. When the live call fails I read the last verdict I stored instead of
    // returning an error. A slightly stale archetype is far more useful to
    // someone looking at their dashboard than a 500.
    Optional<ModelScore> findFirstBySubjectTypeAndSubjectIdOrderByScoredAtDesc(
            String subjectType, Long subjectId);

    // Flagged transactions for one account, newest first. I join back to
    // `transactions` because a score row alone is meaningless in a UI — the user
    // needs the merchant and amount, not a subject_id.
    // I write this as explicit JPQL because it spans two entities that have no
    // JPA relationship between them (see ModelScore's note on why subject_id
    // isn't a real foreign key).
    @Query("""
            SELECT new com.ledgerlens.backend.scoring.FlaggedTransaction(
                t.id, t.plaidTransactionId, t.postedDate, t.amount,
                t.merchant, t.category, s.score, s.modelName, s.scoredAt)
            FROM ModelScore s, Transaction t
            WHERE s.subjectId = t.id
              AND s.subjectType = 'TRANSACTION'
              AND s.label = 'ANOMALY'
              AND t.accountId = :accountId
            ORDER BY s.score DESC""")
    List<FlaggedTransaction> findFlaggedForAccount(Long accountId);
}
