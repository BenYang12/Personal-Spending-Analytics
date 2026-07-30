package com.ledgerlens.backend.scoring;

import com.ledgerlens.backend.scoring.ScoringDtos.AnomalyResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.ArchetypeResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.HealthResponse;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreMonthRequest;
import com.ledgerlens.backend.scoring.ScoringDtos.ScoreTransactionsRequest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// My HTTP client for the Python scoring service.
//
// RestClient is Spring's modern synchronous HTTP client — it replaced
// RestTemplate, and unlike WebClient it doesn't drag in the whole reactive
// stack for what is a plain blocking call. Synchronous is right here: my caller
// genuinely needs the answer before it can respond.
//
// The single most important thing in this class is that IT NEVER THROWS a raw
// HTTP exception at its caller. Every failure becomes a ScoringUnavailable
// signal, because the entire point of Step 19 is that my dashboard keeps
// working when this service is down. A client that propagates connection
// refusals would make graceful degradation impossible one layer up.
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);

    // My own exception type. I catch a wide range of failures — connection
    // refused, timeout, 500, malformed body — and collapse them into this one
    // signal, because from my caller's point of view they all mean the same
    // thing: "no fresh score right now, use the cache".
    public static class ScoringUnavailableException extends RuntimeException {
        public ScoringUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient restClient;

    public ScoringClient(
            @Value("${ledgerlens.scoring.url}") String baseUrl,
            @Value("${ledgerlens.scoring.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${ledgerlens.scoring.read-timeout-ms}") int readTimeoutMs) {

        // TIMEOUTS ARE THE POINT. Java's default HTTP timeout is effectively
        // infinite, so without these a hung scoring service wouldn't return an
        // error — it would hold my request threads open until Tomcat's pool was
        // exhausted, and then my ENTIRE API would stop responding. One slow
        // dependency taking down an unrelated service is how small outages
        // become large ones. A bounded timeout turns that into a fast, local
        // failure I can fall back from.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        log.info("scoring client -> {} (connect {}ms, read {}ms)",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** Score one account-month. Throws ScoringUnavailableException on any failure. */
    public ArchetypeResponse scoreMonth(ScoreMonthRequest request) {
        try {
            return restClient.post()
                    .uri("/score/month")
                    .body(request)
                    .retrieve()
                    .body(ArchetypeResponse.class);
        } catch (RestClientResponseException error) {
            if (isNotEnoughData(error)) {
                log.info("scoring service says not enough data for account={} month={}",
                        request.accountId(), request.month());
                return null;
            }
            throw unavailable("scoring service returned " + error.getStatusCode(), error);
        } catch (ResourceAccessException error) {
            // Connection refused, DNS failure, or a timeout — the service is
            // unreachable rather than unhappy.
            throw unavailable("scoring service unreachable", error);
        } catch (Exception error) {
            // Deliberately broad. Anything unexpected here — a malformed body, a
            // JSON parse failure — must still degrade rather than 500. I'd
            // normally avoid catching Exception, but at a network boundary
            // "never take my API down for a dependency" outranks precision.
            throw unavailable("unexpected error calling scoring service", error);
        }
    }

    /** Score an account's transactions for anomalies. */
    public AnomalyResponse scoreTransactions(ScoreTransactionsRequest request) {
        try {
            return restClient.post()
                    .uri("/score/transactions")
                    .body(request)
                    .retrieve()
                    .body(AnomalyResponse.class);
        } catch (RestClientResponseException error) {
            if (isNotEnoughData(error)) {
                log.info("no scorable transactions for account={}", request.accountId());
                return null;
            }
            throw unavailable("scoring service returned " + error.getStatusCode(), error);
        } catch (ResourceAccessException error) {
            throw unavailable("scoring service unreachable", error);
        } catch (Exception error) {
            throw unavailable("unexpected error calling scoring service", error);
        }
    }

    /**
     * Is the scoring service actually able to work?
     *
     * I check `modelsLoaded`, not just whether the call succeeded. The service
     * deliberately stays up and reports "degraded" when its pickles are missing,
     * so a 200 alone would tell me nothing. That design only pays off if the
     * caller reads the field — otherwise a useless service looks healthy.
     */
    public boolean isHealthy() {
        try {
            HealthResponse health = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(HealthResponse.class);
            return health != null && health.modelsLoaded();
        } catch (Exception error) {
            log.warn("scoring service health check failed: {}", error.getMessage());
            return false;
        }
    }

    /**
     * Is this 422 my service saying "not enough data", or FastAPI saying "your
     * request is malformed"?
     *
     * They share a status code and mean opposite things, which cost me a real
     * bug. I was treating EVERY 422 as "not enough data", so when my backend
     * sent camelCase field names to a service expecting snake_case, FastAPI's
     * validation rejection surfaced in my dashboard as the entirely plausible
     * "Not enough activity this month to identify a pattern". A believable wrong
     * answer, no error anywhere — I only found it by reading the Python log.
     *
     * So I check the BODY, not just the status. My service returns
     * {"error": "not_enough_data"} for the domain case; FastAPI's own validation
     * failures return a {"detail": [...]} array and no `error` key. Anything
     * that isn't explicitly my domain error gets treated as a fault, which is
     * the safe direction: a loud failure beats a plausible lie.
     */
    private boolean isNotEnoughData(RestClientResponseException error) {
        if (error.getStatusCode().value() != 422) {
            return false;
        }
        String body = error.getResponseBodyAsString();
        boolean domainError = body != null && body.contains("\"not_enough_data\"");
        if (!domainError) {
            // Loudly, because this means my two services disagree about the
            // contract — a deploy or schema problem, not a data problem.
            log.error("scoring service rejected my request as INVALID (not a data issue). "
                    + "This usually means the request schema drifted. Body: {}", body);
        }
        return domainError;
    }

    private ScoringUnavailableException unavailable(String message, Throwable cause) {
        // WARN, not ERROR: this is a handled condition with a working fallback,
        // and logging it at ERROR would page someone for something my system
        // already recovered from. Noisy alerts get muted, and muted alerts miss
        // real incidents.
        log.warn("{}: {}", message, cause.getMessage());
        return new ScoringUnavailableException(message, cause);
    }
}
