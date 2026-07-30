package com.ledgerlens.backend.scoring;

import java.time.YearMonth;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The scoring endpoints my dashboard calls.
//
// Thin on purpose: parse parameters, call the service, return. Every decision
// about fallbacks and persistence lives in ScoringService, which is why that
// logic is unit-testable without any HTTP involved.
//
// Note what these endpoints never do: return an error because the model service
// is down. They return `stale: true` instead. That single field is the whole
// user-facing payoff of Step 19 — the dashboard shows real data with an honest
// "not fresh" marker rather than an error page.
@RestController
@RequestMapping("/api/scores")
public class ScoringController {

    private final ScoringService scoringService;
    private final ScoringClient scoringClient;

    public ScoringController(ScoringService scoringService, ScoringClient scoringClient) {
        this.scoringService = scoringService;
        this.scoringClient = scoringClient;
    }

    // GET /api/scores/archetype?accountId=2&month=2026-05
    // A GET that writes to the database is a slight impurity I'm accepting
    // knowingly: scoring caches its result as a side effect. The alternative —
    // making the dashboard POST to get a read — would be worse ergonomics for
    // no real benefit, since the operation is idempotent (re-scoring the same
    // month with the same model replaces the row rather than adding one).
    @GetMapping("/archetype")
    public ScoringService.ArchetypeResult archetype(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return scoringService.scoreMonth(accountId, month);
    }

    // GET /api/scores/anomalies?accountId=2
    @GetMapping("/anomalies")
    public ScoringService.AnomalyScanResult anomalies(@RequestParam Long accountId) {
        return scoringService.scanAnomalies(accountId);
    }

    // POST because rescanning is an explicit user action ("check my account
    // again"), and I want it to read as a command rather than a page load.
    @PostMapping("/rescan")
    public ScoringService.AnomalyScanResult rescan(@RequestParam Long accountId) {
        return scoringService.scanAnomalies(accountId);
    }

    // Lets my dashboard show "ML features temporarily unavailable" BEFORE the
    // user clicks into a view that needs them. Checking upfront is a better
    // experience than letting each panel discover the outage separately.
    @GetMapping("/health")
    public Map<String, Object> scoringHealth() {
        boolean healthy = scoringClient.isHealthy();
        return Map.of(
                "scoringServiceAvailable", healthy,
                "note", healthy
                        ? "Scores are live"
                        : "Scoring service is unavailable — cached scores will be served");
    }
}
