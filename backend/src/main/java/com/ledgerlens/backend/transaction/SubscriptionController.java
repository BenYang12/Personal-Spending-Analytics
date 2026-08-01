package com.ledgerlens.backend.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The "your subscriptions" view.
//
// This is the same recurring-charge detection that feeds the recurring_share
// feature behind my Subscription Creep archetype — one rule, surfaced two ways.
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final TransactionRepository transactions;

    public SubscriptionController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    public record SubscriptionsResponse(
            List<Subscription> subscriptions,
            BigDecimal monthlyTotal,
            BigDecimal annualTotal) {
    }

    // GET /api/subscriptions?accountId=2
    //
    // I return the totals alongside the list because the aggregate is the point:
    // eleven individually-forgettable charges add up to a number worth acting on,
    // and making the client sum them would just invite it to sum them differently.
    @GetMapping
    public SubscriptionsResponse subscriptions(@RequestParam Long accountId) {
        List<Subscription> found = transactions.findRecurringCharges(accountId).stream()
                .map(row -> new Subscription(
                        row.getMerchant(),
                        row.getCategory(),
                        row.getTypicalAmount().setScale(2, RoundingMode.HALF_UP),
                        row.getMonthsSeen(),
                        row.getFirstSeen(),
                        row.getLastSeen()))
                .toList();

        BigDecimal monthlyTotal = found.stream()
                .map(Subscription::typicalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SubscriptionsResponse(
                found,
                monthlyTotal,
                monthlyTotal.multiply(BigDecimal.valueOf(12)));
    }
}
