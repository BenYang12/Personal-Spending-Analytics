package com.ledgerlens.backend.advice;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Turns deterministic ML output into readable budget advice.
//
// The control flow is the whole point of this class:
//
//     try Claude  ->  valid?  -> return it, source = "claude"
//                  \  invalid? -> retry ONCE with the failure described
//                                  \  still invalid? -> rule-based fallback
//     no API key / exception          -> rule-based fallback immediately
//
// The user always gets advice. What varies is how well-written it is. I report
// which path produced it rather than hiding it, because silently degrading is
// how you end up unable to explain your own product's behaviour.
@Service
public class AdviceService {

    private static final Logger log = LoggerFactory.getLogger(AdviceService.class);

    // The system prompt is where I fence the model in. Every line here exists to
    // stop a specific failure I'd otherwise have to catch downstream.
    private static final String SYSTEM_PROMPT = """
            You are a budgeting assistant inside a personal finance app.

            You are given the OUTPUT of a machine learning pipeline that has already \
            analysed the user's transactions: a spending archetype from clustering, \
            category totals, month-over-month changes, and any charges flagged by an \
            anomaly detection model.

            Your job is to write clear, specific advice about those findings.

            Rules:
            - Use ONLY the numbers provided. Never invent a figure, merchant, or category.
            - Do NOT decide what is or is not anomalous. That determination has already \
            been made by the anomaly model; explain its findings, don't second-guess them.
            - Be concrete. Cite actual categories and amounts from the data.
            - Address the user as "you". No greeting, no sign-off, no markdown.
            - If the data is thin, say so plainly rather than padding with generic tips.
            """;

    private final Optional<AnthropicClient> anthropic;
    private final RuleBasedAdvice ruleBasedAdvice;
    private final String model;
    private final long maxTokens;

    // Optional<AnthropicClient> is how I depend on a bean that may not exist.
    // Spring injects an empty Optional when no AnthropicClient bean was created
    // (see AnthropicConfig), so a missing API key is an ordinary runtime state
    // rather than a startup failure.
    public AdviceService(Optional<AnthropicClient> anthropic,
                         RuleBasedAdvice ruleBasedAdvice,
                         @Value("${ledgerlens.anthropic.model}") String model,
                         @Value("${ledgerlens.anthropic.max-tokens}") long maxTokens) {
        this.anthropic = anthropic;
        this.ruleBasedAdvice = ruleBasedAdvice;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** Advice plus how it was produced — the client shows the fallback state honestly. */
    public record AdviceResult(BudgetAdvice advice, String source, String note) {}

    public AdviceResult generate(AdviceInputs inputs) {
        if (!inputs.hasEnoughData()) {
            return new AdviceResult(null, "none",
                    "Not enough activity this month to generate advice");
        }

        if (anthropic.isEmpty()) {
            log.debug("no Anthropic client configured, using rule-based advice");
            return fallback(inputs, "No language model configured");
        }

        // Attempt 1.
        Optional<BudgetAdvice> first = callClaude(inputs, null);
        if (first.isPresent() && first.get().isValid()) {
            return new AdviceResult(first.get(), "claude", null);
        }

        // Attempt 2 — ONE retry, and I tell the model what was wrong with the
        // first answer rather than repeating the identical prompt. A blind retry
        // of a deterministic-ish failure mostly reproduces the same failure; the
        // correction is what makes the second attempt worth paying for.
        //
        // Exactly one retry, deliberately: this sits in a user-facing request, so
        // the latency budget is small, and if two attempts both fail my fallback
        // is right there. Retrying harder to avoid a good fallback is backwards.
        log.warn("first advice attempt was invalid, retrying once");
        Optional<BudgetAdvice> second = callClaude(inputs,
                "Your previous response was rejected because it did not contain exactly "
                        + "three complete recommendations with non-empty titles and details. "
                        + "Return exactly three.");

        if (second.isPresent() && second.get().isValid()) {
            return new AdviceResult(second.get(), "claude-retry", null);
        }

        log.warn("both advice attempts failed validation, falling back to rules");
        return fallback(inputs, "Language model response could not be validated");
    }

    private Optional<BudgetAdvice> callClaude(AdviceInputs inputs, String correction) {
        try {
            String prompt = buildPrompt(inputs);
            if (correction != null) {
                prompt = prompt + "\n\n" + correction;
            }

            // .outputConfig(BudgetAdvice.class) is the structured-outputs call:
            // the SDK derives a JSON schema from my record and the API constrains
            // generation to match it. This is what makes "parse the LLM's JSON"
            // a non-problem — there is no parsing step I can get wrong, and no
            // stray prose or code fence to strip.
            StructuredMessageCreateParams<BudgetAdvice> params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(prompt)
                    .outputConfig(BudgetAdvice.class)
                    .build();

            return anthropic.get().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(typed -> typed.text())
                    .findFirst();

        } catch (Exception error) {
            // Deliberately broad, exactly as in my scoring client: at a
            // third-party network boundary, "never take my API down for a
            // dependency" outranks precise exception typing. Everything here has
            // the same remedy — use the fallback.
            log.warn("Anthropic call failed: {}", error.getMessage());
            return Optional.empty();
        }
    }

    private AdviceResult fallback(AdviceInputs inputs, String reason) {
        return new AdviceResult(ruleBasedAdvice.generate(inputs), "rule-based", reason);
    }

    // I hand the model a compact text rendering rather than raw JSON. Two
    // reasons: it reads more naturally to a language model, and building it by
    // hand guarantees I know exactly which fields left my server — no risk of a
    // serializer helpfully including a field I never intended to share.
    private String buildPrompt(AdviceInputs inputs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Month: ").append(inputs.month()).append('\n');
        prompt.append("Spending archetype (from clustering): ").append(inputs.archetype());
        if (inputs.archetypeDescription() != null) {
            prompt.append(" — ").append(inputs.archetypeDescription());
        }
        prompt.append('\n');

        if (inputs.totalSpend() != null) {
            prompt.append("Total discretionary spend: $").append(inputs.totalSpend()).append('\n');
        }
        if (inputs.recurringSpend() != null) {
            prompt.append("Of which recurring subscriptions: $")
                    .append(inputs.recurringSpend()).append('\n');
        }

        prompt.append("\nTop categories:\n");
        for (var category : inputs.topCategories()) {
            prompt.append("  - ").append(category.category())
                    .append(": $").append(category.amount())
                    .append(" (").append(Math.round(category.shareOfSpend() * 100))
                    .append("% of spending)\n");
        }

        if (inputs.notableChanges() != null && !inputs.notableChanges().isEmpty()) {
            prompt.append("\nMonth-over-month changes:\n");
            for (var change : inputs.notableChanges()) {
                prompt.append("  - ").append(change.category())
                        .append(": $").append(change.lastMonth())
                        .append(" -> $").append(change.thisMonth())
                        .append(" (").append(Math.round(change.percentChange())).append("%)\n");
            }
        }

        prompt.append("\nCharges flagged as unusual by the anomaly detection model:\n");
        if (inputs.flaggedCharges() == null || inputs.flaggedCharges().isEmpty()) {
            prompt.append("  (none this month)\n");
        } else {
            for (var charge : inputs.flaggedCharges()) {
                prompt.append("  - $").append(charge.amount())
                        .append(" at ").append(charge.merchant())
                        .append(" on ").append(charge.date())
                        .append(" — flagged because: ")
                        .append(String.join("; ", charge.reasons()))
                        .append('\n');
            }
        }

        prompt.append("\nWrite the budget advice for this month.");
        return prompt.toString();
    }

    /** Exposed so my controller can report LLM availability without a live call. */
    public boolean isLlmAvailable() {
        return anthropic.isPresent();
    }
}
