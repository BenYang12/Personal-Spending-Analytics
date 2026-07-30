package com.ledgerlens.backend.advice;

import com.ledgerlens.backend.scoring.FlaggedTransaction;
import com.ledgerlens.backend.scoring.ModelScoreRepository;
import com.ledgerlens.backend.scoring.MonthlyFeatureRepository;
import com.ledgerlens.backend.scoring.ScoringService;
import com.ledgerlens.backend.transaction.CategorySummary;
import com.ledgerlens.backend.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Gathers my ML output into AdviceInputs, manages the cache, and calls the
// advice service. This is the seam where "what the models decided" becomes
// "what the language model is allowed to see".
@Service
public class AdviceAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdviceAssembler.class);

    // I only surface the categories that actually matter. Handing the model
    // fifteen categories including three $4 rows produces advice about the $4
    // rows — the constraint improves the output, it doesn't just save tokens.
    private static final int TOP_CATEGORIES = 5;

    private final ScoringService scoringService;
    private final TransactionRepository transactions;
    private final ModelScoreRepository scores;
    private final MonthlyFeatureRepository monthlyFeatures;
    private final AdviceService adviceService;
    private final CachedAdviceRepository cache;
    private final ObjectMapper objectMapper;

    public AdviceAssembler(ScoringService scoringService,
                           TransactionRepository transactions,
                           ModelScoreRepository scores,
                           MonthlyFeatureRepository monthlyFeatures,
                           AdviceService adviceService,
                           CachedAdviceRepository cache,
                           ObjectMapper objectMapper) {
        this.scoringService = scoringService;
        this.transactions = transactions;
        this.scores = scores;
        this.monthlyFeatures = monthlyFeatures;
        this.adviceService = adviceService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public record AdviceResponse(Long accountId, String month, BudgetAdvice advice,
                                 String source, boolean cached, String note) {}

    @Transactional
    public AdviceResponse adviceFor(Long accountId, YearMonth month) {
        AdviceInputs inputs = gatherInputs(accountId, month);

        if (!inputs.hasEnoughData()) {
            return new AdviceResponse(accountId, month.toString(), null, "none", false,
                    "Not enough activity this month to generate advice");
        }

        String hash = hashInputs(inputs);
        LocalDate monthStart = month.atDay(1);

        // Cache hit only when the INPUTS match, not merely the month — see the
        // note on CachedAdvice.inputsHash for why that distinction matters.
        Optional<CachedAdvice> existing = cache.findByAccountIdAndMonth(accountId, monthStart);
        if (existing.isPresent() && hash.equals(existing.get().getInputsHash())) {
            BudgetAdvice cached = readAdvice(existing.get().getAdvice());
            if (cached != null) {
                log.debug("serving cached advice for account={} month={}", accountId, month);
                return new AdviceResponse(accountId, month.toString(), cached,
                        existing.get().getSource(), true, null);
            }
        }

        AdviceService.AdviceResult result = adviceService.generate(inputs);
        if (result.advice() != null) {
            persist(existing, accountId, monthStart, result, hash);
        }

        return new AdviceResponse(accountId, month.toString(), result.advice(),
                result.source(), false, result.note());
    }

    // ------------------------------------------------------------------
    // gathering the ML output
    // ------------------------------------------------------------------

    private AdviceInputs gatherInputs(Long accountId, YearMonth month) {
        // The archetype comes from my clustering model via the scoring service.
        // If that service is down this returns a cached or empty result — advice
        // then degrades along with it rather than failing outright.
        ScoringService.ArchetypeResult archetype = scoringService.scoreMonth(accountId, month);

        List<CategorySummary> thisMonth = transactions.summarizeByCategory(
                accountId, month.atDay(1), month.atEndOfMonth());
        List<CategorySummary> lastMonth = transactions.summarizeByCategory(
                accountId, month.minusMonths(1).atDay(1), month.minusMonths(1).atEndOfMonth());

        // Outflows only. Including income would make every "share of spending"
        // percentage meaningless, and a negative total would make them absurd.
        BigDecimal totalSpend = thisMonth.stream()
                .map(CategorySummary::total)
                .filter(amount -> amount.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AdviceInputs.CategoryShare> topCategories = new ArrayList<>();
        thisMonth.stream()
                .filter(category -> category.total().compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> b.total().compareTo(a.total()))
                .limit(TOP_CATEGORIES)
                .forEach(category -> topCategories.add(new AdviceInputs.CategoryShare(
                        category.category(),
                        category.total().setScale(2, RoundingMode.HALF_UP),
                        share(category.total(), totalSpend))));

        return new AdviceInputs(
                month.toString(),
                archetype.archetype(),
                archetype.description(),
                topCategories,
                buildDeltas(thisMonth, lastMonth),
                flaggedForMonth(accountId, month),
                totalSpend.setScale(2, RoundingMode.HALF_UP),
                recurringSpend(accountId, month, totalSpend));
    }

    private List<AdviceInputs.CategoryDelta> buildDeltas(List<CategorySummary> thisMonth,
                                                        List<CategorySummary> lastMonth) {
        List<AdviceInputs.CategoryDelta> deltas = new ArrayList<>();
        for (CategorySummary current : thisMonth) {
            if (current.total().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal previous = lastMonth.stream()
                    .filter(row -> row.category().equals(current.category()))
                    .map(CategorySummary::total)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);

            // A category with no prior month has an undefined percent change,
            // not an infinite one. I skip it rather than emit a number the model
            // would faithfully repeat as "up 100%".
            if (previous.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            double change = current.total().subtract(previous)
                    .divide(previous, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100;

            // Only movements big enough to be worth a sentence.
            if (Math.abs(change) >= 20) {
                deltas.add(new AdviceInputs.CategoryDelta(
                        current.category(),
                        current.total().setScale(2, RoundingMode.HALF_UP),
                        previous.setScale(2, RoundingMode.HALF_UP),
                        Math.round(change * 10) / 10.0));
            }
        }
        return deltas;
    }

    private List<AdviceInputs.FlaggedCharge> flaggedForMonth(Long accountId, YearMonth month) {
        List<AdviceInputs.FlaggedCharge> flagged = new ArrayList<>();
        for (FlaggedTransaction transaction : scores.findFlaggedForAccount(accountId)) {
            if (YearMonth.from(transaction.postedDate()).equals(month)) {
                flagged.add(new AdviceInputs.FlaggedCharge(
                        transaction.merchant(),
                        transaction.amount().setScale(2, RoundingMode.HALF_UP),
                        transaction.postedDate().toString(),
                        // The evidence from my anomaly model. The LLM explains
                        // this; it does not produce it.
                        //
                        // Phrasing matters: my templates read "flagged because
                        // {reason}", so a reason starting with "flagged by…"
                        // produced "flagged because flagged by the anomaly
                        // model". Reason strings have to be sentence fragments
                        // that complete "because …" — a small thing that was
                        // only obvious once I read real rendered output.
                        List.of("its anomaly score of "
                                + transaction.anomalyScore().setScale(3, RoundingMode.HALF_UP)
                                + " is well above the flagging threshold")));
            }
        }
        return flagged;
    }

    // Recurring spend comes from the feature vector Python computed and my
    // scoring integration stored — I don't recompute subscription detection in
    // Java, for the same training/serving-skew reason as everywhere else.
    private BigDecimal recurringSpend(Long accountId, YearMonth month, BigDecimal totalSpend) {
        return monthlyFeatures.findByAccountIdAndMonth(accountId, month.atDay(1))
                .map(features -> {
                    try {
                        var node = objectMapper.readTree(features.getFeatures());
                        double share = node.path("recurring_share").asDouble(0.0);
                        return totalSpend.multiply(BigDecimal.valueOf(share))
                                .setScale(2, RoundingMode.HALF_UP);
                    } catch (JacksonException error) {
                        log.warn("could not read recurring_share from stored features", error);
                        return BigDecimal.ZERO;
                    }
                })
                .orElse(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // cache plumbing
    // ------------------------------------------------------------------

    private void persist(Optional<CachedAdvice> existing, Long accountId, LocalDate month,
                         AdviceService.AdviceResult result, String hash) {
        String json = objectMapper.writeValueAsString(result.advice());
        existing.ifPresentOrElse(
                row -> row.refresh(result.source(), json, hash),
                () -> cache.save(new CachedAdvice(accountId, month, result.source(), json, hash)));
    }

    private BudgetAdvice readAdvice(String json) {
        try {
            return objectMapper.readValue(json, BudgetAdvice.class);
        } catch (JacksonException error) {
            // A cache row I can't read is not worth failing a request over —
            // it's derived data. Log it and regenerate.
            log.warn("could not deserialise cached advice, regenerating", error);
            return null;
        }
    }

    // SHA-256 over the serialised inputs. I need a stable fingerprint of "the
    // data this advice describes"; hashCode() would be wrong here because it
    // isn't stable across JVM runs, so cached advice would look invalid after
    // every restart.
    private String hashInputs(AdviceInputs inputs) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(inputs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            // SHA-256 is required by the Java spec, so this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private double share(BigDecimal amount, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        return amount.divide(total, 4, RoundingMode.HALF_UP).doubleValue();
    }
}
