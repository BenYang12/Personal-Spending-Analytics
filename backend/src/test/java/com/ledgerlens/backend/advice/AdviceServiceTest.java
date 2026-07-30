package com.ledgerlens.backend.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlens.backend.advice.BudgetAdvice.Recommendation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Tests for the advice layer.
//
// What I test here is the POLICY, not the prose: that a missing API key degrades
// instead of failing, that a malformed model response is caught by my validator,
// and that the fallback always satisfies the same contract the LLM has to.
//
// I deliberately don't assert on generated wording. Asserting that the model
// says something specific would make the suite fail whenever the model was
// upgraded — testing Anthropic rather than testing my code.
class AdviceServiceTest {

    private final RuleBasedAdvice ruleBasedAdvice = new RuleBasedAdvice();

    private AdviceInputs richInputs() {
        return new AdviceInputs(
                "2026-05",
                "Weekend Spender",
                "Spends mostly on weekends, eats out often",
                List.of(
                        new AdviceInputs.CategoryShare("dining", new BigDecimal("820.00"), 0.41),
                        new AdviceInputs.CategoryShare("groceries", new BigDecimal("410.00"), 0.20),
                        new AdviceInputs.CategoryShare("shopping", new BigDecimal("300.00"), 0.15)),
                List.of(new AdviceInputs.CategoryDelta("dining", new BigDecimal("820.00"),
                        new BigDecimal("500.00"), 64.0)),
                List.of(new AdviceInputs.FlaggedCharge("TechWorld Electronics",
                        new BigDecimal("1899.00"), "2026-05-17",
                        List.of("Amount is 68.8 standard deviations above your usual"))),
                new BigDecimal("2000.00"),
                new BigDecimal("340.00"));
    }

    private AdviceInputs quietInputs() {
        return new AdviceInputs("2026-05", "Balanced Spender", "Nothing notable",
                List.of(new AdviceInputs.CategoryShare("groceries", new BigDecimal("120.00"), 0.60)),
                List.of(), List.of(),
                new BigDecimal("200.00"), new BigDecimal("10.00"));
    }

    // ------------------------------------------------------------------
    // graceful degradation — the contract that matters most
    // ------------------------------------------------------------------

    @Nested
    class WithoutAnApiKey {

        // Optional.empty() is exactly what Spring injects when AnthropicConfig
        // returns null because no key was set — so this is the real no-key path,
        // not an approximation of it.
        private final AdviceService service = new AdviceService(
                Optional.empty(), ruleBasedAdvice, "claude-opus-5", 2000L);

        @Test
        @DisplayName("still returns complete advice, marked as rule-based")
        void degradesInsteadOfFailing() {
            AdviceService.AdviceResult result = service.generate(richInputs());

            assertThat(result.advice()).isNotNull();
            assertThat(result.source()).isEqualTo("rule-based");
            // The note is user-facing honesty: my dashboard renders this rather
            // than implying the words came from a model.
            assertThat(result.note()).contains("No language model configured");
        }

        @Test
        @DisplayName("the fallback satisfies the same contract the LLM must")
        void fallbackPassesItsOwnValidator() {
            // The point of this test: a fallback that couldn't pass my validator
            // would be a fallback I could never actually serve.
            assertThat(service.generate(richInputs()).advice().isValid()).isTrue();
            assertThat(service.generate(quietInputs()).advice().isValid()).isTrue();
        }

        @Test
        @DisplayName("reports LLM availability without making a call")
        void reportsAvailability() {
            assertThat(service.isLlmAvailable()).isFalse();
        }

        @Test
        @DisplayName("thin data returns no advice rather than inventing some")
        void refusesToPadThinData() {
            AdviceInputs empty = new AdviceInputs("2026-05", null, null,
                    List.of(), List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);

            AdviceService.AdviceResult result = service.generate(empty);

            assertThat(result.advice()).isNull();
            assertThat(result.source()).isEqualTo("none");
        }
    }

    // ------------------------------------------------------------------
    // the validator — my defence against schema-valid but useless output
    // ------------------------------------------------------------------

    @Nested
    class Validation {

        private Recommendation ok(String title) {
            return new Recommendation(title, "A specific detail citing $100.", 100);
        }

        @Test
        @DisplayName("accepts a well-formed response")
        void acceptsValid() {
            assertThat(new BudgetAdvice("A summary.",
                    List.of(ok("One"), ok("Two"), ok("Three")), "Explanation.").isValid())
                    .isTrue();
        }

        @Test
        @DisplayName("rejects the wrong number of recommendations")
        void rejectsWrongCount() {
            // Structured outputs guarantee the FIELD is a list; they cannot
            // guarantee it holds exactly three items. That gap is precisely why
            // I validate content on top of the schema.
            assertThat(new BudgetAdvice("A summary.", List.of(ok("One"), ok("Two")),
                    "Explanation.").isValid()).isFalse();
            assertThat(new BudgetAdvice("A summary.",
                    List.of(ok("1"), ok("2"), ok("3"), ok("4")), "Explanation.").isValid())
                    .isFalse();
        }

        @Test
        @DisplayName("rejects blank text the schema would happily allow")
        void rejectsBlankFields() {
            assertThat(new BudgetAdvice("   ", List.of(ok("A"), ok("B"), ok("C")), "X.")
                    .isValid()).isFalse();
            assertThat(new BudgetAdvice("Summary.",
                    List.of(ok("A"), ok("B"), new Recommendation("", "detail", 0)), "X.")
                    .isValid()).isFalse();
            assertThat(new BudgetAdvice("Summary.", List.of(ok("A"), ok("B"), ok("C")), "")
                    .isValid()).isFalse();
        }

        @Test
        @DisplayName("rejects nulls without throwing")
        void rejectsNullsSafely() {
            // A validator that NPEs on malformed input defeats its own purpose —
            // it turns a handled failure into an unhandled one.
            assertThat(new BudgetAdvice(null, null, null).isValid()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // the structured-output contract
    // ------------------------------------------------------------------

    @Nested
    class StructuredOutputSchema {

        @Test
        @DisplayName("a JSON schema can be derived from BudgetAdvice")
        void schemaDerives() {
            // I have no API key in CI, so I can't make a live call — but I CAN
            // prove the half that would otherwise only fail in production.
            //
            // .outputConfig(BudgetAdvice.class) generates a JSON schema by
            // reflecting over my record. If I ever add a field of a type the
            // schema generator can't express, this throws — and without this
            // test I'd discover it on the first real request with a real key.
            var params = com.anthropic.models.messages.MessageCreateParams.builder()
                    .model("claude-opus-5")
                    .maxTokens(2000L)
                    .addUserMessage("test")
                    .outputConfig(BudgetAdvice.class)
                    .build();

            // StructuredMessageCreateParams<T> is a thin wrapper: outputType()
            // is the class the response gets deserialised into, rawParams() is
            // the underlying request carrying the generated schema.
            assertThat(params.outputType()).isEqualTo(BudgetAdvice.class);
            assertThat(params.rawParams().outputConfig()).isPresent();

            // And the schema really describes MY record — if the generator
            // silently produced an empty object schema, the API would accept it
            // and I'd get back well-formed JSON with none of my fields.
            String schema = params.rawParams().outputConfig().toString();
            assertThat(schema)
                    .contains("summary")
                    .contains("recommendations")
                    .contains("flaggedChargeExplanation")
                    // The nested record's fields must be in there too.
                    .contains("estimatedMonthlySaving")
                    // isValid() is @JsonIgnore'd, so the model must never be
                    // asked to fill in my own validation flag.
                    .doesNotContain("valid");
        }
    }

    // ------------------------------------------------------------------
    // rule-based generation
    // ------------------------------------------------------------------

    @Nested
    class RuleBasedGeneration {

        @Test
        @DisplayName("always produces exactly three recommendations")
        void alwaysThree() {
            // Both a data-rich and a data-poor month, because the padding path is
            // the one that would silently break the contract.
            assertThat(ruleBasedAdvice.generate(richInputs()).recommendations()).hasSize(3);
            assertThat(ruleBasedAdvice.generate(quietInputs()).recommendations()).hasSize(3);
        }

        @Test
        @DisplayName("cites real numbers from the data, not generic filler")
        void citesRealNumbers() {
            BudgetAdvice advice = ruleBasedAdvice.generate(richInputs());

            String allText = advice.summary() + advice.recommendations().stream()
                    .map(r -> r.title() + r.detail()).reduce("", String::concat);

            // Dining is 41% of spend, so it must be named. Advice that could have
            // been written without looking at the data is advice I don't want.
            assertThat(allText).containsIgnoringCase("dining");
        }

        @Test
        @DisplayName("explains the largest flagged charge specifically")
        void explainsTheFlaggedCharge() {
            BudgetAdvice advice = ruleBasedAdvice.generate(richInputs());

            assertThat(advice.flaggedChargeExplanation())
                    .contains("TechWorld Electronics")
                    .contains("1899.00");
        }

        @Test
        @DisplayName("says nothing was unusual when nothing was flagged")
        void handlesNoFlaggedCharges() {
            // The empty case has to read naturally, not as a missing value —
            // this string is rendered directly in my dashboard.
            assertThat(ruleBasedAdvice.generate(quietInputs()).flaggedChargeExplanation())
                    .containsIgnoringCase("nothing looked unusual");
        }

        @Test
        @DisplayName("picks the largest charge when several were flagged")
        void picksTheLargestFlaggedCharge() {
            AdviceInputs inputs = new AdviceInputs("2026-05", "Weekend Spender", "desc",
                    List.of(new AdviceInputs.CategoryShare("dining", new BigDecimal("100"), 0.5)),
                    List.of(),
                    List.of(
                            new AdviceInputs.FlaggedCharge("Small Shop", new BigDecimal("42.00"),
                                    "2026-05-02", List.of("novel merchant")),
                            new AdviceInputs.FlaggedCharge("Big Purchase", new BigDecimal("1899.00"),
                                    "2026-05-17", List.of("very large amount"))),
                    new BigDecimal("200"), new BigDecimal("0"));

            assertThat(ruleBasedAdvice.generate(inputs).flaggedChargeExplanation())
                    .contains("Big Purchase")
                    .doesNotContain("Small Shop");
        }

        @Test
        @DisplayName("estimated savings are never negative")
        void savingsAreNeverNegative() {
            // A recommendation promising a negative saving would be nonsense in
            // the UI, and the arithmetic that produces it isn't obviously safe.
            for (Recommendation recommendation :
                    ruleBasedAdvice.generate(richInputs()).recommendations()) {
                assertThat(recommendation.estimatedMonthlySaving()).isGreaterThanOrEqualTo(0.0);
            }
        }
    }
}
