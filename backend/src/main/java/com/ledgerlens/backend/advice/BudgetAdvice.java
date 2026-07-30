package com.ledgerlens.backend.advice;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

// The EXACT shape I require back from Claude — my strict JSON contract.
//
// I don't ask for JSON in the prompt and hope. The Anthropic SDK derives a JSON
// schema from this record and the API constrains generation to it, so the model
// physically cannot return prose, a code fence, or a missing field. That turns
// "parse the LLM's output and pray" into an ordinary typed method call.
//
// The @JsonPropertyDescription annotations are load-bearing: they become the
// field descriptions in the generated schema, which is how I tell the model what
// each field means without burning prompt tokens repeating myself.
//
// Note these are Jackson 2 annotations (com.fasterxml.jackson.annotation) while
// the rest of my Spring app is on Jackson 3 (tools.jackson) — the Anthropic SDK
// brings its own. Both coexist because the package names differ entirely.
public record BudgetAdvice(

        @JsonPropertyDescription(
                "A two-sentence plain-English summary of this month's spending behaviour. "
                        + "Address the user directly as 'you'. No greeting, no preamble.")
        String summary,

        @JsonPropertyDescription(
                "Exactly three concrete, actionable budget recommendations. Each must "
                        + "reference a specific category or merchant from the data provided "
                        + "and suggest a specific action. No generic advice like 'spend less'.")
        List<Recommendation> recommendations,

        @JsonPropertyDescription(
                "A one-sentence explanation of the single most unusual flagged charge, "
                        + "written for someone who is not technical. If no charges were "
                        + "flagged, state that nothing looked unusual this month.")
        String flaggedChargeExplanation) {

    public record Recommendation(

            @JsonPropertyDescription("A short imperative title, at most 8 words.")
            String title,

            @JsonPropertyDescription(
                    "One or two sentences explaining the recommendation, citing the "
                            + "specific number from the data that motivates it.")
            String detail,

            @JsonPropertyDescription(
                    "Estimated monthly dollar saving if the user follows this, as a "
                            + "number. Use 0 when a saving cannot be estimated from the data.")
            double estimatedMonthlySaving) {
    }

    // @JsonIgnore because Jackson treats isValid() as a bean property and was
    // serialising `"valid": true` into my API responses — an internal check
    // leaking into the public contract, spotted only by reading real output.
    // It also would have gone into the JSON schema sent to Claude, asking the
    // model to fill in a field that is mine to compute.
    @com.fasterxml.jackson.annotation.JsonIgnore
    // I validate the CONTENT even though the schema already guaranteed the shape.
    // Structured outputs enforce types and required fields; they cannot enforce
    // "exactly three recommendations" or "non-empty summary". Those are my rules,
    // so they are my job — and a response that satisfies the schema while being
    // useless is exactly what triggers my retry.
    public boolean isValid() {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        if (recommendations == null || recommendations.size() != 3) {
            return false;
        }
        for (Recommendation recommendation : recommendations) {
            if (recommendation == null
                    || recommendation.title() == null || recommendation.title().isBlank()
                    || recommendation.detail() == null || recommendation.detail().isBlank()) {
                return false;
            }
        }
        return flaggedChargeExplanation != null && !flaggedChargeExplanation.isBlank();
    }
}
