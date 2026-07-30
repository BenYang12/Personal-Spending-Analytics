package com.ledgerlens.backend.advice;

import java.math.BigDecimal;
import java.util.List;

// EXACTLY what the LLM is allowed to see — and nothing else.
//
// This record is the architectural boundary of my whole project. Everything in
// here is deterministic output from my ML pipeline: the archetype KMeans
// assigned, the category totals Postgres aggregated, the charges IsolationForest
// flagged. The model's job is to write English about numbers I already decided.
//
// What is deliberately ABSENT matters more than what's present:
//   - No raw transaction list. The LLM never sees the ledger.
//   - No merchant history, no account identifiers, no access tokens.
//   - No ability to decide what counts as anomalous. That verdict arrived from
//     IsolationForest with a measured precision/recall (see ml/EVALUATION.md);
//     asking a language model to re-litigate it would replace a number I can
//     defend with a vibe I can't.
//
// This is the "LLM strictly downstream of deterministic ML" claim made concrete.
// If someone asks me in an interview how I keep the model from hallucinating a
// fraud alert, the answer is that it structurally cannot — it is never given the
// data or the authority to make that call.
public record AdviceInputs(

        String month,
        String archetype,
        String archetypeDescription,

        // Top spending categories with their share of the month, so the model can
        // cite a real percentage rather than inventing one.
        List<CategoryShare> topCategories,

        // Month-over-month movement — the "what changed" the advice hangs on.
        List<CategoryDelta> notableChanges,

        // The charges MY model flagged, with the reasons MY code generated.
        List<FlaggedCharge> flaggedCharges,

        BigDecimal totalSpend,
        BigDecimal recurringSpend) {

    public record CategoryShare(String category, BigDecimal amount, double shareOfSpend) {}

    public record CategoryDelta(String category, BigDecimal thisMonth, BigDecimal lastMonth,
                                double percentChange) {}

    public record FlaggedCharge(String merchant, BigDecimal amount, String date,
                                List<String> reasons) {}

    /** True when there's enough substance to be worth asking a model about. */
    public boolean hasEnoughData() {
        return archetype != null && topCategories != null && !topCategories.isEmpty();
    }
}
