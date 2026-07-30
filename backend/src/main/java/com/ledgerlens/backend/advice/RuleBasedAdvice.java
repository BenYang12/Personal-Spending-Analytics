package com.ledgerlens.backend.advice;

import com.ledgerlens.backend.advice.BudgetAdvice.Recommendation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// Deterministic advice, generated from the same ML output the LLM would get.
//
// I wrote this BEFORE the Claude integration, deliberately. It is the floor my
// product stands on: no API key, an outage, a rate limit, or a malformed
// response and the user still gets useful, specific advice. The LLM is an
// upgrade to the wording, not the source of the insight.
//
// That ordering also proved something worth knowing — the *insight* was never
// coming from the language model. Every recommendation below is derived from
// numbers my pipeline already computed. What Claude adds is fluency and
// synthesis, which is real value but a different kind of value than I assumed
// before I wrote this class.
//
// It's also the honest answer to "what if the LLM is down?" — I don't have to
// say "the feature breaks", I can say "it degrades to this, and here it is".
@Component
public class RuleBasedAdvice {

    // Thresholds tuned against my seeded and synthetic data. Constants rather
    // than magic numbers inline, so the policy is visible in one place.
    private static final double HIGH_CATEGORY_SHARE = 0.30;
    private static final double NOTABLE_INCREASE = 25.0;
    private static final BigDecimal HIGH_RECURRING_SHARE = new BigDecimal("0.15");

    public BudgetAdvice generate(AdviceInputs inputs) {
        return new BudgetAdvice(
                buildSummary(inputs),
                buildRecommendations(inputs),
                explainFlaggedCharge(inputs));
    }

    private String buildSummary(AdviceInputs inputs) {
        StringBuilder summary = new StringBuilder();
        summary.append("Your spending this month looks like a ")
                .append(inputs.archetype() == null ? "typical" : inputs.archetype().toLowerCase())
                .append(" pattern");

        if (inputs.topCategories() != null && !inputs.topCategories().isEmpty()) {
            var top = inputs.topCategories().getFirst();
            summary.append(", led by ").append(top.category())
                    .append(" at ").append(percent(top.shareOfSpend()))
                    .append(" of your discretionary spending");
        }
        summary.append(". ");

        int flagged = inputs.flaggedCharges() == null ? 0 : inputs.flaggedCharges().size();
        summary.append(flagged == 0
                ? "Nothing in your transactions looked unusual."
                : flagged + (flagged == 1 ? " charge was" : " charges were") + " flagged as unusual.");
        return summary.toString();
    }

    // Always returns exactly three, because that's my contract — I build a
    // candidate list from the strongest signals and pad from a generic pool if
    // the data was quiet. Returning two would fail my own validator, and a
    // fallback that fails validation is not a fallback.
    private List<Recommendation> buildRecommendations(AdviceInputs inputs) {
        List<Recommendation> found = new ArrayList<>();

        // Signal 1: a category eating an outsized share of the month.
        if (inputs.topCategories() != null) {
            for (var category : inputs.topCategories()) {
                if (found.size() >= 3) break;
                if (category.shareOfSpend() >= HIGH_CATEGORY_SHARE) {
                    BigDecimal target = category.amount()
                            .multiply(new BigDecimal("0.15"))
                            .setScale(2, RoundingMode.HALF_UP);
                    found.add(new Recommendation(
                            "Trim " + category.category() + " spending",
                            category.category() + " was " + percent(category.shareOfSpend())
                                    + " of your discretionary spending (" + money(category.amount())
                                    + "). Cutting it by 15% would free about " + money(target) + " a month.",
                            target.doubleValue()));
                }
            }
        }

        // Signal 2: a category that jumped versus last month.
        if (inputs.notableChanges() != null) {
            for (var change : inputs.notableChanges()) {
                if (found.size() >= 3) break;
                if (change.percentChange() >= NOTABLE_INCREASE) {
                    BigDecimal increase = change.thisMonth().subtract(change.lastMonth())
                            .setScale(2, RoundingMode.HALF_UP);
                    found.add(new Recommendation(
                            "Check your rising " + change.category() + " costs",
                            change.category() + " rose " + Math.round(change.percentChange())
                                    + "% from " + money(change.lastMonth()) + " to "
                                    + money(change.thisMonth()) + ". Returning to last month's level "
                                    + "would save " + money(increase) + ".",
                            increase.doubleValue()));
                }
            }
        }

        // Signal 3: subscription creep — the archetype my clustering names.
        if (found.size() < 3 && inputs.recurringSpend() != null && inputs.totalSpend() != null
                && inputs.totalSpend().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal share = inputs.recurringSpend()
                    .divide(inputs.totalSpend(), 4, RoundingMode.HALF_UP);
            if (share.compareTo(HIGH_RECURRING_SHARE) >= 0) {
                found.add(new Recommendation(
                        "Audit your recurring charges",
                        money(inputs.recurringSpend()) + " of your spending is recurring "
                                + "subscriptions — " + percent(share.doubleValue())
                                + " of the total. Cancelling the ones you no longer use is the "
                                + "easiest saving available to you.",
                        inputs.recurringSpend().multiply(new BigDecimal("0.25"))
                                .setScale(2, RoundingMode.HALF_UP).doubleValue()));
            }
        }

        // Pad to exactly three. Generic, but honest and still actionable.
        List<Recommendation> filler = List.of(
                new Recommendation("Set a weekly spending check-in",
                        "Reviewing your transactions once a week catches drift before it "
                                + "becomes a habit, and takes about five minutes.", 0),
                new Recommendation("Give your largest category a monthly cap",
                        "A specific number to stay under is far easier to act on than a "
                                + "general intention to spend less.", 0),
                new Recommendation("Move one recurring purchase to a cheaper tier",
                        "Downgrading a single subscription you rarely use is a one-time "
                                + "action that saves every month afterwards.", 0));

        for (Recommendation recommendation : filler) {
            if (found.size() >= 3) break;
            found.add(recommendation);
        }
        return found.subList(0, 3);
    }

    private String explainFlaggedCharge(AdviceInputs inputs) {
        if (inputs.flaggedCharges() == null || inputs.flaggedCharges().isEmpty()) {
            return "Nothing looked unusual in your spending this month.";
        }
        // The largest flagged charge is the one a user cares about first.
        var charge = inputs.flaggedCharges().stream()
                .max((a, b) -> a.amount().compareTo(b.amount()))
                .orElseThrow();

        String reason = (charge.reasons() == null || charge.reasons().isEmpty())
                ? "it doesn't match your usual pattern"
                : charge.reasons().getFirst().toLowerCase();

        return "The " + money(charge.amount()) + " charge at " + charge.merchant()
                + " on " + charge.date() + " was flagged because " + reason + ".";
    }

    private String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
