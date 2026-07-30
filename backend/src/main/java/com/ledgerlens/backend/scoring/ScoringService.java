package com.ledgerlens.backend.scoring;

// Jackson 3, which is what Spring Boot 4 ships. Two things changed from the
// Jackson 2 I'd have found in every tutorial: the package is `tools.jackson`
// rather than `com.fasterxml.jackson`, and its exceptions are now UNCHECKED
// (JacksonException extends RuntimeException). Only the annotations stayed on
// the old `com.fasterxml.jackson.annotation` package, which is why the imports
// in ScoringDtos look inconsistent with these — that inconsistency is Jackson's,
// not mine.
import com.ledgerlens.backend.scoring.ScoringClient.ScoringUnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ledgerlens.backend.scoring.ScoringDtos.AnomalyResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.AnomalyResult;
import com.ledgerlens.backend.scoring.ScoringDtos.ArchetypeResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.RawTransaction;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreMonthRequest;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreTransactionsRequest;
import com.ledgerlens.backend.transaction.Transaction;
import com.ledgerlens.backend.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Orchestrates scoring: gather transactions, call the model service, persist the
// verdict, and — the part that matters most — degrade gracefully when the model
// service isn't there.
//
// The degradation contract I'm implementing:
//
//   scoring service UP    -> fresh score, persisted, stale=false
//   scoring service DOWN  -> last stored score returned, stale=true
//   down AND never scored -> empty result, NOT an error
//
// The principle: a stale archetype is far more useful to someone looking at
// their dashboard than a 500. Money data must stay visible even when the ML
// layer is unavailable — the transactions are facts and don't depend on a model
// being up. Marking the response `stale` rather than hiding it is the honest
// version: the user still sees their data AND knows it isn't fresh.
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final ScoringClient client;
    private final TransactionRepository transactions;
    private final ModelScoreRepository scores;
    private final MonthlyFeatureRepository monthlyFeatures;
    private final ObjectMapper objectMapper;

    public ScoringService(ScoringClient client,
                          TransactionRepository transactions,
                          ModelScoreRepository scores,
                          MonthlyFeatureRepository monthlyFeatures,
                          ObjectMapper objectMapper) {
        this.client = client;
        this.transactions = transactions;
        this.scores = scores;
        this.monthlyFeatures = monthlyFeatures;
        // ObjectMapper is auto-configured by Boot, so I just ask for it rather
        // than constructing one — Jackson instances are expensive to build and
        // are thread-safe once configured.
        this.objectMapper = objectMapper;
    }

    // What my controller returns. `stale` is the whole point: the client can
    // render "scores from 2 hours ago" instead of pretending the data is live
    // or showing an error page.
    public record ArchetypeResult(
            Long accountId, String month, String archetype, String description,
            Integer cluster, BigDecimal distanceToCentroid,
            List<ScoringDtos.FeatureEvidence> evidence,
            String modelVersion, boolean stale, String note) {
    }

    public record AnomalyScanResult(
            Long accountId, int scored, int flagged,
            List<FlaggedTransaction> flaggedTransactions,
            String modelVersion, boolean stale, String note) {
    }

    // ------------------------------------------------------------------
    // archetype
    // ------------------------------------------------------------------

    @Transactional
    public ArchetypeResult scoreMonth(Long accountId, YearMonth month) {
        // I send the account's FULL history, not just the target month.
        // Recurring-charge detection needs three months to recognise a
        // subscription, so a single month would silently produce
        // recurring_share = 0 and quietly misclassify the archetype.
        List<Transaction> history = transactions.findByAccountIdAndPostedDateBetween(
                accountId, month.minusMonths(11).atDay(1), month.atEndOfMonth());

        if (history.isEmpty()) {
            return empty(accountId, month, "No transactions for this account");
        }

        List<RawTransaction> payload = history.stream().map(this::toRaw).toList();

        try {
            ArchetypeResponse response = client.scoreMonth(
                    new ScoreMonthRequest(accountId, month.toString(), payload));

            if (response == null) {
                // The service answered honestly that this month can't be scored
                // (too few transactions). That's a real answer, not a failure.
                return empty(accountId, month, "Not enough activity this month to identify a pattern");
            }

            Long featureId = persistFeatures(accountId, month, response.features());
            persistScore(ModelScore.SUBJECT_MONTH, featureId, response.modelVersion(),
                    response.distanceToCentroid(), response.archetype());

            return new ArchetypeResult(accountId, month.toString(), response.archetype(),
                    response.description(), response.cluster(), response.distanceToCentroid(),
                    response.evidence(), response.modelVersion(), false, null);

        } catch (ScoringUnavailableException error) {
            // THE FALLBACK. The model service is down, so I serve the last
            // verdict I stored rather than failing the request.
            return cachedArchetype(accountId, month);
        }
    }

    private ArchetypeResult cachedArchetype(Long accountId, YearMonth month) {
        Optional<MonthlyFeature> features =
                monthlyFeatures.findByAccountIdAndMonth(accountId, month.atDay(1));

        if (features.isPresent()) {
            Optional<ModelScore> cached = scores
                    .findFirstBySubjectTypeAndSubjectIdOrderByScoredAtDesc(
                            ModelScore.SUBJECT_MONTH, features.get().getId());
            if (cached.isPresent()) {
                ModelScore score = cached.get();
                log.info("serving CACHED archetype for account={} month={} (scored {})",
                        accountId, month, score.getScoredAt());
                return new ArchetypeResult(accountId, month.toString(), score.getLabel(),
                        "Scoring service is unavailable — showing the last result I stored.",
                        null, score.getScore(), List.of(), score.getModelName(), true,
                        "Last scored " + score.getScoredAt());
            }
        }

        // Never scored AND the service is down. I return an empty result rather
        // than an error: the user's transactions are still perfectly viewable,
        // and only the archetype badge is missing.
        log.info("no cached archetype available for account={} month={}", accountId, month);
        return new ArchetypeResult(accountId, month.toString(), null, null, null, null,
                List.of(), null, true,
                "Scoring service is unavailable and I have no earlier result for this month");
    }

    // ------------------------------------------------------------------
    // anomalies
    // ------------------------------------------------------------------

    @Transactional
    public AnomalyScanResult scanAnomalies(Long accountId) {
        List<Transaction> all = transactions.findByAccountIdAndPostedDateBetween(
                accountId, LocalDate.now().minusYears(2), LocalDate.now().plusDays(1));

        if (all.isEmpty()) {
            return new AnomalyScanResult(accountId, 0, 0, List.of(), null, false,
                    "No transactions for this account");
        }

        // A lookup from Plaid's id back to my internal id. The scoring service
        // returns transaction_ids, and I match on those rather than on list
        // POSITION — it drops inflows and refunds before scoring, so the result
        // list is shorter than what I sent. Position-matching would mislabel
        // every transaction after the first dropped one, and it would look
        // plausible while doing it.
        Map<String, Long> idByPlaidId = new HashMap<>();
        for (Transaction transaction : all) {
            idByPlaidId.put(transaction.getPlaidTransactionId(), transaction.getId());
        }

        List<RawTransaction> payload = all.stream().map(this::toRaw).toList();

        try {
            AnomalyResponse response = client.scoreTransactions(
                    new ScoreTransactionsRequest(accountId, payload));

            if (response == null) {
                return new AnomalyScanResult(accountId, 0, 0, List.of(), null, false,
                        "No scorable transactions");
            }

            for (AnomalyResult result : response.results()) {
                Long localId = idByPlaidId.get(result.transactionId());
                if (localId == null) {
                    // The service scored something I don't recognise. I skip it
                    // loudly instead of guessing, since a mismatched id means my
                    // two sides disagree about the data.
                    log.warn("scored unknown transaction id {}", result.transactionId());
                    continue;
                }
                persistScore(ModelScore.SUBJECT_TRANSACTION, localId, response.modelVersion(),
                        result.anomalyScore(), result.isAnomaly() ? "ANOMALY" : "NORMAL");
            }

            List<FlaggedTransaction> flagged = scores.findFlaggedForAccount(accountId);
            log.info("scored {} transactions for account={}, {} flagged",
                    response.results().size(), accountId, response.flaggedCount());

            return new AnomalyScanResult(accountId, response.results().size(),
                    response.flaggedCount(), flagged, response.modelVersion(), false, null);

        } catch (ScoringUnavailableException error) {
            // Same fallback shape: previously stored flags are still useful, and
            // far better than an error page.
            List<FlaggedTransaction> cached = scores.findFlaggedForAccount(accountId);
            log.info("serving {} CACHED flagged transactions for account={}", cached.size(), accountId);
            return new AnomalyScanResult(accountId, 0, cached.size(), cached, null, true,
                    "Scoring service is unavailable — showing previously flagged charges");
        }
    }

    // ------------------------------------------------------------------
    // persistence helpers
    // ------------------------------------------------------------------

    /** Upsert the feature vector for an account-month, returning its id. */
    private Long persistFeatures(Long accountId, YearMonth month, Map<String, Double> features) {
        String json = writeJson(features);
        LocalDate monthStart = month.atDay(1);

        return monthlyFeatures.findByAccountIdAndMonth(accountId, monthStart)
                .map(existing -> {
                    // Loaded inside a @Transactional method, so Hibernate's dirty
                    // checking writes the UPDATE at commit — no save() needed.
                    existing.updateFeatures(json);
                    return existing.getId();
                })
                .orElseGet(() -> monthlyFeatures
                        .save(new MonthlyFeature(accountId, monthStart, json)).getId());
    }

    /** Upsert a score. Re-scoring replaces the verdict rather than duplicating it. */
    private void persistScore(String subjectType, Long subjectId, String modelName,
                              BigDecimal score, String label) {
        scores.findBySubjectTypeAndSubjectIdAndModelName(subjectType, subjectId, modelName)
                .ifPresentOrElse(
                        existing -> existing.update(score, label),
                        () -> scores.save(new ModelScore(subjectType, subjectId, modelName, score, label)));
    }

    private String writeJson(Map<String, Double> features) {
        try {
            return objectMapper.writeValueAsString(features == null ? Map.of() : features);
        } catch (JacksonException error) {
            // A map of doubles cannot fail to serialise, so this is genuinely
            // impossible — but I refuse to swallow it silently and return null,
            // which would violate the NOT NULL on that column much later and
            // much more confusingly.
            throw new IllegalStateException("could not serialise features", error);
        }
    }

    private RawTransaction toRaw(Transaction transaction) {
        return new RawTransaction(
                transaction.getPlaidTransactionId(),
                transaction.getAccountId(),
                transaction.getPostedDate(),
                transaction.getAmount(),
                transaction.getMerchant(),
                transaction.getCategory(),
                transaction.isPending());
    }

    private ArchetypeResult empty(Long accountId, YearMonth month, String note) {
        return new ArchetypeResult(accountId, month.toString(), null, null, null, null,
                List.of(), null, false, note);
    }
}
