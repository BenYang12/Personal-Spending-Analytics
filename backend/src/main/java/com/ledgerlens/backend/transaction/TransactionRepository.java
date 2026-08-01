package com.ledgerlens.backend.transaction;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    // "Derived queries": Spring Data PARSES THE METHOD NAME into SQL at startup
    // FindBy + AccountId + And + PostedDate + Between becomes:
    //  WHERE account_id = ? AND posted_date BETWEEN ? AND ?
    List<Transaction> findByAccountIdAndPostedDateBetween(
        Long accountId, LocalDate start, LocalDate end
    );

    List<Transaction> findByCategory(String category);

   
    // Method names can't express GROUP BY ->  eed explicit JPQL. Note it
    // queries the ENTITY (Transaction, t.postedDate), not the table: Hibernate
    // translates to SQL. "new ..." is a constructor expression: each result
    // row is poured straight into a CategorySummary (full package name
    // required by JPQL). :accountId binds the method parameter by name.
    @Query("""
            SELECT new com.ledgerlens.backend.transaction.CategorySummary(
                t.category, SUM(t.amount), COUNT(t))
            FROM Transaction t
            WHERE t.accountId = :accountId AND t.postedDate BETWEEN :start AND :end
            GROUP BY t.category
            ORDER BY SUM(t.amount) DESC""")
    List<CategorySummary> summarizeByCategory(Long accountId, LocalDate start, LocalDate end);

    java.util.Optional<Transaction> findByPlaidTransactionId(String plaidTransactionId);
    // Derived DELETE: "In" takes a collection -> WHERE plaid_transaction_id IN (...).
    // Returns the count so we can report how many removals actually applied.
    // @Transactional is required for derived deletes (the service supplies it).
    long deleteByPlaidTransactionIdIn(java.util.Collection<String> plaidTransactionIds);

    /**
     * Detect recurring charges for the "your subscriptions" view.
     *
     * A subscription is the same (merchant, amount) charged in at least 3
     * distinct months, AT ROUGHLY MONTHLY CADENCE. Details in the SQL:
     *
     * - Group on ROUND(amount), so cent-level drift (a tax change, an FX rate)
     *   doesn't split one subscription into two.
     * - Group by DATE_TRUNC('month', ...) to count distinct MONTHS, not dates —
     *   twelve charges in one month is not a subscription.
     * - `SUM(charges_in_month) <= COUNT(*) * 1.5` is the cadence check, and it
     *   exists because of a false positive I hit immediately: my daily coffee
     *   at Blue Bottle was being reported as a subscription. It recurs, the
     *   amount is stable-ish, it spans six months — it satisfies every "is this
     *   recurring" test while being obviously not a subscription. What separates
     *   them is FREQUENCY: Netflix charges ~1x/month, coffee ~11x/month.
     * - `b.total_charges >= t.merchant_charges * 0.8` is the dominance check,
     *   and it caught a second, subtler false positive: "Harris Teeter $70.04,
     *   3 months". Groceries range $35-90, so with ~47 of them SOME rounded
     *   bucket lands in three different months by pure coincidence — and that
     *   coincidence passes both the month count and the cadence test. The
     *   distinguishing property is that a subscription merchant is charged at
     *   essentially ONE amount: Netflix is 6 of 6 charges in its bucket, Harris
     *   Teeter is 3 of 15. Requiring the bucket to cover 80% of the merchant's
     *   activity separates them.
     * - `DISTINCT ON (merchant)` collapses a merchant whose amounts land in
     *   several rounded buckets. Without it Blue Bottle appeared three times in
     *   the same list, once per price point.
     *
     * KNOWN DIVERGENCE, deliberate and worth stating: ml/features.py's
     * flag_recurring has no cadence check, so the recurring_share feature behind
     * my Subscription Creep archetype still counts high-frequency habits. That
     * rule is imperfect in the same way this one was. I haven't aligned them yet
     * because changing a feature definition means retraining both models and
     * re-verifying every metric published in the README — real work that
     * shouldn't ride along with a UI change. Tracked as a follow-up.
     *
     * Native SQL rather than JPQL because DATE_TRUNC and DISTINCT ON are
     * Postgres features JPQL can't express. Acceptable for a read-only reporting
     * query; it does tie this one method to Postgres.
     */
    @Query(value = """
            WITH monthly AS (
                SELECT merchant,
                       ROUND(amount)    AS amount_key,
                       MIN(category)    AS category,
                       AVG(amount)      AS amount,
                       COUNT(*)         AS charges_in_month,
                       MIN(posted_date) AS first_seen,
                       MAX(posted_date) AS last_seen
                FROM transactions
                WHERE account_id = :accountId AND amount > 0
                GROUP BY merchant, ROUND(amount), DATE_TRUNC('month', posted_date)
            ),
            merchant_totals AS (
                SELECT merchant, COUNT(*) AS merchant_charges
                FROM transactions
                WHERE account_id = :accountId AND amount > 0
                GROUP BY merchant
            ),
            buckets AS (
                SELECT merchant,
                       MIN(category)         AS category,
                       AVG(amount)           AS typical_amount,
                       COUNT(*)              AS months_seen,
                       SUM(charges_in_month) AS total_charges,
                       MIN(first_seen)       AS first_seen,
                       MAX(last_seen)        AS last_seen
                FROM monthly
                GROUP BY merchant, amount_key
                HAVING COUNT(*) >= 3
                   AND SUM(charges_in_month) <= COUNT(*) * 1.5
            )
            SELECT DISTINCT ON (b.merchant)
                   b.merchant, b.category, b.typical_amount,
                   b.months_seen, b.first_seen, b.last_seen
            FROM buckets b
            JOIN merchant_totals t ON t.merchant = b.merchant
            WHERE b.total_charges >= t.merchant_charges * 0.8
            ORDER BY b.merchant, b.typical_amount DESC
            """, nativeQuery = true)
    List<SubscriptionRow> findRecurringCharges(Long accountId);

    /**
     * Spring Data projects native-query results onto an interface by matching
     * getter names to column aliases — a record won't work here because native
     * queries have no constructor-expression syntax.
     */
    interface SubscriptionRow {
        String getMerchant();
        String getCategory();
        java.math.BigDecimal getTypicalAmount();
        long getMonthsSeen();
        LocalDate getFirstSeen();
        LocalDate getLastSeen();
    }

}
