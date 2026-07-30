package com.ledgerlens.backend.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlens.backend.scoring.ScoringClient.ScoringUnavailableException;
import com.ledgerlens.backend.scoring.ScoringDtos.AnomalyResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.AnomalyResult;
import com.ledgerlens.backend.scoring.ScoringDtos.ArchetypeResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreMonthRequest;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreTransactionsRequest;
import com.ledgerlens.backend.transaction.Transaction;
import com.ledgerlens.backend.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

// Tests for the behaviour that justifies this whole step: my API keeps working
// when the ML service doesn't.
//
// That's exactly the kind of thing nobody tests, because testing it means
// simulating an outage — and then it's discovered in production, at the worst
// possible time. Mockito makes the outage trivial to stage: I tell the client
// mock to throw, and assert my service degrades instead of collapsing.
//
// These are plain unit tests. No Spring context, no database, no HTTP — so they
// run in milliseconds and I'll actually keep running them.
@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock private ScoringClient client;
    @Mock private TransactionRepository transactions;
    @Mock private ModelScoreRepository scores;
    @Mock private MonthlyFeatureRepository monthlyFeatures;

    private ScoringService service;

    private static final Long ACCOUNT_ID = 2L;
    private static final YearMonth MONTH = YearMonth.of(2026, 5);
    private static final String MODEL_VERSION = "kmeans-k6+iforest-c0.02";

    @BeforeEach
    void setUp() {
        // A real ObjectMapper rather than a mock: it's a pure function here, and
        // mocking it would prove nothing while hiding real serialisation bugs.
        service = new ScoringService(client, transactions, scores, monthlyFeatures,
                new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Transaction transaction(Long id, String plaidId, String merchant, double amount) {
        Transaction transaction = new Transaction(plaidId, ACCOUNT_ID, LocalDate.of(2026, 5, 17),
                BigDecimal.valueOf(amount), merchant, "dining", false);
        // The database assigns ids, so my entity has no setter. Reflection lets
        // a test simulate a persisted row without weakening the production API
        // with a setter nobody should ever call.
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    private ArchetypeResponse archetypeResponse() {
        return new ArchetypeResponse(ACCOUNT_ID, "2026-05", 0, "Weekend Spender",
                "Spends mostly on weekends", BigDecimal.valueOf(1.96), List.of(),
                MODEL_VERSION, Map.of("txn_count", 24.0, "weekend_ratio", 0.78));
    }

    private MonthlyFeature monthlyFeature(Long id) {
        MonthlyFeature feature = new MonthlyFeature(ACCOUNT_ID, MONTH.atDay(1), "{}");
        ReflectionTestUtils.setField(feature, "id", id);
        return feature;
    }

    private ModelScore cachedScore(String label, double score) {
        ModelScore modelScore = new ModelScore(ModelScore.SUBJECT_MONTH, 99L, MODEL_VERSION,
                BigDecimal.valueOf(score), label);
        ReflectionTestUtils.setField(modelScore, "scoredAt", Instant.parse("2026-05-20T10:00:00Z"));
        return modelScore;
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("live scoring returns a fresh result and persists it")
    void scoresAndPersists() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        when(client.scoreMonth(any())).thenReturn(archetypeResponse());
        when(monthlyFeatures.findByAccountIdAndMonth(anyLong(), any())).thenReturn(Optional.empty());
        when(monthlyFeatures.save(any())).thenReturn(monthlyFeature(99L));
        when(scores.findBySubjectTypeAndSubjectIdAndModelName(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());

        ScoringService.ArchetypeResult result = service.scoreMonth(ACCOUNT_ID, MONTH);

        assertThat(result.archetype()).isEqualTo("Weekend Spender");
        assertThat(result.stale()).isFalse();
        assertThat(result.modelVersion()).isEqualTo(MODEL_VERSION);

        // The features must be stored alongside the score. Without them the
        // score is unauditable — I could never answer "why was I labelled this?"
        verify(monthlyFeatures).save(any(MonthlyFeature.class));

        ArgumentCaptor<ModelScore> saved = ArgumentCaptor.forClass(ModelScore.class);
        verify(scores).save(saved.capture());
        assertThat(saved.getValue().getLabel()).isEqualTo("Weekend Spender");
        assertThat(saved.getValue().getSubjectType()).isEqualTo(ModelScore.SUBJECT_MONTH);
        // subject_id must point at the monthly_features row, not the account.
        assertThat(saved.getValue().getSubjectId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("I send the account's wider history, not just the target month")
    void sendsHistoryNotJustTheMonth() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        when(client.scoreMonth(any())).thenReturn(archetypeResponse());
        when(monthlyFeatures.findByAccountIdAndMonth(anyLong(), any())).thenReturn(Optional.empty());
        when(monthlyFeatures.save(any())).thenReturn(monthlyFeature(99L));
        when(scores.findBySubjectTypeAndSubjectIdAndModelName(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());

        service.scoreMonth(ACCOUNT_ID, MONTH);

        // Recurring-charge detection needs three months to recognise a
        // subscription. If I only sent the target month, recurring_share would
        // silently come back 0 and the archetype would be wrong — with nothing
        // failing. So I assert the date range explicitly.
        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactions).findByAccountIdAndPostedDateBetween(
                anyLong(), start.capture(), any());
        assertThat(start.getValue()).isBefore(MONTH.atDay(1).minusMonths(6));
    }

    // ------------------------------------------------------------------
    // graceful degradation — the point of this step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("service down + cached score -> returns the cached score marked stale")
    void fallsBackToCachedScore() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        // The outage, staged in one line.
        when(client.scoreMonth(any()))
                .thenThrow(new ScoringUnavailableException("connection refused", new RuntimeException()));
        when(monthlyFeatures.findByAccountIdAndMonth(anyLong(), any()))
                .thenReturn(Optional.of(monthlyFeature(99L)));
        when(scores.findFirstBySubjectTypeAndSubjectIdOrderByScoredAtDesc(anyString(), anyLong()))
                .thenReturn(Optional.of(cachedScore("Weekend Spender", 1.96)));

        ScoringService.ArchetypeResult result = service.scoreMonth(ACCOUNT_ID, MONTH);

        // The user still sees their archetype...
        assertThat(result.archetype()).isEqualTo("Weekend Spender");
        // ...and is told it isn't fresh. Showing stale data as if it were live
        // would be the dishonest version of this feature.
        assertThat(result.stale()).isTrue();
        assertThat(result.note()).contains("Last scored");

        // Nothing is written during an outage: I must never persist a cached
        // read back as if it were a new scoring result, or its timestamp would
        // creep forward and the data would look fresher than it is.
        verify(scores, never()).save(any());
    }

    @Test
    @DisplayName("service down + no cache -> empty result, still not an error")
    void degradesWithoutCache() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        when(client.scoreMonth(any()))
                .thenThrow(new ScoringUnavailableException("timeout", new RuntimeException()));
        when(monthlyFeatures.findByAccountIdAndMonth(anyLong(), any())).thenReturn(Optional.empty());

        ScoringService.ArchetypeResult result = service.scoreMonth(ACCOUNT_ID, MONTH);

        // No exception. The archetype badge is simply absent, while the rest of
        // the dashboard — the transactions, which are facts and don't need a
        // model — carries on working.
        assertThat(result.archetype()).isNull();
        assertThat(result.stale()).isTrue();
        assertThat(result.note()).contains("unavailable");
    }

    @Test
    @DisplayName("anomaly scan degrades to previously flagged charges")
    void anomalyScanFallsBackToStoredFlags() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "TechWorld", 1899.0)));
        when(client.scoreTransactions(any()))
                .thenThrow(new ScoringUnavailableException("connection refused", new RuntimeException()));
        when(scores.findFlaggedForAccount(anyLong())).thenReturn(List.of(
                new FlaggedTransaction(1L, "t1", LocalDate.of(2026, 5, 17),
                        BigDecimal.valueOf(1899.0), "TechWorld", "shopping",
                        BigDecimal.valueOf(0.71), MODEL_VERSION, Instant.now())));

        ScoringService.AnomalyScanResult result = service.scanAnomalies(ACCOUNT_ID);

        assertThat(result.stale()).isTrue();
        assertThat(result.flaggedTransactions()).hasSize(1);
        // Previously flagged charges are still worth showing — the fraud didn't
        // stop being suspicious because my model service restarted.
        assertThat(result.flaggedTransactions().getFirst().merchant()).isEqualTo("TechWorld");
    }

    // ------------------------------------------------------------------
    // id matching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("results are matched by transaction id, never by list position")
    void matchesResultsByIdNotPosition() {
        // The scoring service drops inflows and refunds before scoring, so it
        // legitimately returns FEWER results than I sent. Here I send three and
        // get two back, for the FIRST and THIRD transactions.
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(
                        transaction(10L, "plaid-a", "Bartaco", 42.0),
                        transaction(20L, "plaid-b", "Payroll", -2150.0),
                        transaction(30L, "plaid-c", "TechWorld", 1899.0)));
        when(client.scoreTransactions(any())).thenReturn(new AnomalyResponse(List.of(
                new AnomalyResult("plaid-a", BigDecimal.valueOf(0.42), false, List.of()),
                new AnomalyResult("plaid-c", BigDecimal.valueOf(0.71), true, List.of("big"))),
                1, MODEL_VERSION));
        when(scores.findBySubjectTypeAndSubjectIdAndModelName(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(scores.findFlaggedForAccount(anyLong())).thenReturn(List.of());

        service.scanAnomalies(ACCOUNT_ID);

        // If I'd zipped by position, "plaid-c" would have been written against
        // transaction 20 (the payroll) — every row after the dropped one
        // mislabelled, and it would have looked entirely plausible.
        ArgumentCaptor<ModelScore> saved = ArgumentCaptor.forClass(ModelScore.class);
        verify(scores, org.mockito.Mockito.times(2)).save(saved.capture());

        Map<Long, String> labelsById = saved.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ModelScore::getSubjectId, ModelScore::getLabel));
        assertThat(labelsById).containsEntry(10L, "NORMAL");
        assertThat(labelsById).containsEntry(30L, "ANOMALY");
        // The payroll row was never scored, so it must have no verdict at all.
        assertThat(labelsById).doesNotContainKey(20L);
    }

    @Test
    @DisplayName("an unknown transaction id is skipped, not guessed at")
    void skipsUnknownTransactionIds() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(10L, "plaid-a", "Bartaco", 42.0)));
        when(client.scoreTransactions(any())).thenReturn(new AnomalyResponse(List.of(
                new AnomalyResult("plaid-a", BigDecimal.valueOf(0.42), false, List.of()),
                new AnomalyResult("who-is-this", BigDecimal.valueOf(0.9), true, List.of())),
                1, MODEL_VERSION));
        when(scores.findBySubjectTypeAndSubjectIdAndModelName(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(scores.findFlaggedForAccount(anyLong())).thenReturn(List.of());

        service.scanAnomalies(ACCOUNT_ID);

        // Only the recognised one is stored. A mismatched id means my two
        // services disagree about the data, and guessing would corrupt scores.
        verify(scores, org.mockito.Mockito.times(1)).save(any());
    }

    // ------------------------------------------------------------------
    // idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("re-scoring updates the existing verdict instead of duplicating it")
    void rescoringIsIdempotent() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        when(client.scoreMonth(any())).thenReturn(archetypeResponse());

        MonthlyFeature existingFeature = monthlyFeature(99L);
        ModelScore existingScore = new ModelScore(ModelScore.SUBJECT_MONTH, 99L, MODEL_VERSION,
                BigDecimal.valueOf(9.99), "Old Archetype");
        when(monthlyFeatures.findByAccountIdAndMonth(anyLong(), any()))
                .thenReturn(Optional.of(existingFeature));
        when(scores.findBySubjectTypeAndSubjectIdAndModelName(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.of(existingScore));

        service.scoreMonth(ACCOUNT_ID, MONTH);

        // Nothing new is inserted — the loaded entities are mutated and
        // Hibernate's dirty checking writes the UPDATEs at commit. Same pattern
        // as my Plaid sync in Step 8.
        verify(scores, never()).save(any());
        verify(monthlyFeatures, never()).save(any());
        assertThat(existingScore.getLabel()).isEqualTo("Weekend Spender");
    }

    // ------------------------------------------------------------------
    // empty inputs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an account with no transactions never calls the scoring service")
    void skipsTheCallWhenThereIsNothingToScore() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of());

        ScoringService.ArchetypeResult result = service.scoreMonth(ACCOUNT_ID, MONTH);

        assertThat(result.archetype()).isNull();
        assertThat(result.stale()).isFalse();      // not stale — just nothing to say
        // No point paying for a network round trip to score an empty list.
        verify(client, never()).scoreMonth(any(ScoreMonthRequest.class));
    }

    @Test
    @DisplayName("the service answering 'not enough data' is a real answer, not an outage")
    void notEnoughDataIsNotStale() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(transaction(1L, "t1", "Bartaco", 42.0)));
        // My client returns null for the service's honest 422.
        when(client.scoreMonth(any())).thenReturn(null);

        ScoringService.ArchetypeResult result = service.scoreMonth(ACCOUNT_ID, MONTH);

        assertThat(result.archetype()).isNull();
        // stale=false matters here: nothing is out of date, there simply isn't
        // enough activity to describe. My dashboard shows different copy for
        // "not enough data" than for "scoring is down", so conflating them
        // would mislead the user.
        assertThat(result.stale()).isFalse();
        assertThat(result.note()).contains("Not enough activity");
    }

    @Test
    @DisplayName("an empty account never calls the anomaly scorer either")
    void skipsAnomalyScanWhenEmpty() {
        when(transactions.findByAccountIdAndPostedDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of());

        ScoringService.AnomalyScanResult result = service.scanAnomalies(ACCOUNT_ID);

        assertThat(result.flagged()).isZero();
        assertThat(result.stale()).isFalse();
        verify(client, never()).scoreTransactions(any(ScoreTransactionsRequest.class));
    }
}
