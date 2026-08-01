package com.ledgerlens.backend.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

// One detected recurring charge, for my dashboard's subscriptions view.
//
// Populated directly by a JPQL constructor expression, so the database does the
// grouping and I never load whole Transaction entities just to count them.
public record Subscription(
        String merchant,
        String category,
        BigDecimal typicalAmount,
        long monthsSeen,
        LocalDate firstSeen,
        LocalDate lastSeen) {

    // Annualised cost is the number that actually motivates cancelling
    // something: "$15.49/month" is easy to ignore, "$185.88/year" is not.
    // Computed here rather than in SQL because it's presentation, not data.
    public BigDecimal annualCost() {
        return typicalAmount.multiply(BigDecimal.valueOf(12));
    }
}
