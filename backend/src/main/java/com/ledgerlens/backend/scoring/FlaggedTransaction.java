package com.ledgerlens.backend.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

// A flagged charge joined back to the transaction it refers to.
//
// This is what my anomalies view actually needs. A ModelScore row on its own
// says "subject 4127 scored 0.71", which is useless in a UI — the user needs to
// see "$1,899 at TechWorld Electronics on 17 May". Records make ideal
// projections: JPQL constructs one per result row via a constructor expression,
// so the database does the join and I never load whole entities I don't need.
public record FlaggedTransaction(
        Long transactionId,
        String plaidTransactionId,
        LocalDate postedDate,
        BigDecimal amount,
        String merchant,
        String category,
        BigDecimal anomalyScore,
        String modelName,
        Instant scoredAt) {
}
