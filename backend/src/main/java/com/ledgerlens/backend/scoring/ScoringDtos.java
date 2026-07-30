package com.ledgerlens.backend.scoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

// The wire contract with my Python scoring service, as Java records.
//
// These mirror the Pydantic models in scoring/schemas.py. Two independent
// definitions of one contract is a real risk — they can drift — so I mitigate
// it two ways: the field names match exactly, and the service stamps a
// model_version on every response that I store with the score. If they ever do
// drift, my integration test fails rather than production quietly misbehaving.
// (The heavier fix would be generating these from the service's OpenAPI schema,
// which FastAPI publishes for free. Worth it on a bigger team; overkill here.)
public final class ScoringDtos {

    private ScoringDtos() {}

    // SNAKE_CASE ON EVERY DTO IN THIS FILE, and it cost me a genuinely
    // instructive bug to learn why.
    //
    // Java convention is camelCase (`accountId`); Python and JSON convention is
    // snake_case (`account_id`). Jackson serialises field names literally, so my
    // backend was sending {"accountId": 2} to a service whose Pydantic model
    // requires `account_id`. FastAPI rejected it with 422 "Field required".
    //
    // What made this genuinely nasty is what happened next: my client maps 422
    // to "not enough data to score", so the failure surfaced in my dashboard as
    // the perfectly plausible message "Not enough activity this month to
    // identify a pattern". Not an error. Not a stack trace. A believable,
    // completely wrong answer — the worst kind of bug, and only visible because
    // I checked the scoring service's own log.
    //
    // I apply the strategy PER DTO rather than globally: a global setting would
    // also rewrite my own public API's responses and break the endpoints my
    // dashboard already consumes. This translation belongs at the boundary
    // where the two conventions actually meet.
    // --- everything below is snake_case on the wire ---

    // A transaction as the scoring service wants it. Note I send RAW rows, not
    // features: the Python side owns feature engineering so there's exactly one
    // implementation of it. Recomputing those 26 definitions here in Java is
    // precisely the training/serving skew I'm avoiding.
    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RawTransaction(
            String transactionId,
            Long accountId,
            LocalDate postedDate,
            BigDecimal amount,
            String merchant,
            String category,
            boolean pending) {
    }

    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreMonthRequest(Long accountId, String month, List<RawTransaction> transactions) {}

    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreTransactionsRequest(Long accountId, List<RawTransaction> transactions) {}

    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeatureEvidence(
            String feature,
            BigDecimal yourValue,
            BigDecimal populationAverage,
            BigDecimal stdDevsFromAverage) {
    }

    // @JsonIgnoreProperties(ignoreUnknown = true) is deliberate, not laziness.
    // It means the Python service can ADD a response field without breaking my
    // backend — the two deploy independently, so tolerating unknown fields is
    // what lets me ship them out of step. Strictness here would turn every
    // additive change into a coordinated release.
    @JsonIgnoreProperties(ignoreUnknown = true)
    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArchetypeResponse(
            Long accountId,
            String month,
            Integer cluster,
            String archetype,
            String description,
            BigDecimal distanceToCentroid,
            List<FeatureEvidence> evidence,
            String modelVersion,
            Map<String, Double> features) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnomalyResult(
            String transactionId,
            BigDecimal anomalyScore,
            boolean isAnomaly,
            List<String> reasons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnomalyResponse(
            List<AnomalyResult> results,
            int flaggedCount,
            String modelVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HealthResponse(
            String status,
            boolean modelsLoaded,
            String modelVersion,
            int archetypeCount) {
    }
}
