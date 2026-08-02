import type { Advice } from "@/lib/api";
import { money } from "@/lib/format";
import { Card, Empty, Pill } from "./Card";

/**
 * Budget advice, with its provenance always visible.
 *
 * The source badge is never hidden. When Claude wrote the text it says
 * "AI-generated"; when the fallback did, it says "Rule-based". Showing it only
 * on failure would quietly imply AI authorship is the norm, and would hide the
 * architecture that makes this feature reliable — the whole point is that the
 * page still works with no API key.
 *
 * The text here is downstream of everything else: the model that wrote it only
 * ever saw the archetype, the category totals, and the flagged charges. It never
 * saw a raw transaction and had no say in what counted as anomalous.
 */
export default function AdvicePanel({ advice }: { advice: Advice | null }) {
  if (!advice) {
    return (
      <Card id="advice" title="Budget advice">
        <Empty>Could not load advice.</Empty>
      </Card>
    );
  }

  if (!advice.advice) {
    return (
      <Card id="advice" title="Budget advice">
        <Empty>{advice.note ?? "No advice available for this month."}</Empty>
      </Card>
    );
  }

  const isAi = advice.source === "claude" || advice.source === "claude-retry";
  const { summary, recommendations, flaggedChargeExplanation } = advice.advice;

  return (
    <Card
      id="advice"
      title="Budget advice"
      badge={
        <div className="flex gap-2">
          {advice.cached && <Pill>Cached</Pill>}
          <Pill tone={isAi ? "accent" : "neutral"}>
            {isAi ? "AI-generated" : "Rule-based"}
          </Pill>
        </div>
      }
    >
      <p className="text-sm leading-relaxed text-zinc-800">{summary}</p>

      <ol className="mt-4 space-y-3">
        {recommendations.map((recommendation, index) => (
          <li key={recommendation.title} className="flex gap-3">
            <span
              aria-hidden="true"
              className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center
                         rounded-full bg-zinc-900 text-[11px] font-semibold text-white"
            >
              {index + 1}
            </span>
            <div>
              <p className="text-sm font-medium text-zinc-900">
                {recommendation.title}
                {recommendation.estimatedMonthlySaving > 0 && (
                  <span className="ml-2 font-normal text-teal-700">
                    save ~{money(recommendation.estimatedMonthlySaving)}/mo
                  </span>
                )}
              </p>
              <p className="mt-0.5 text-sm text-zinc-600">{recommendation.detail}</p>
            </div>
          </li>
        ))}
      </ol>

      <p className="mt-4 border-t border-zinc-100 pt-3 text-xs text-zinc-600">
        {flaggedChargeExplanation}
      </p>

      {/* Stating the constraint in the product, not just the README. This is the
          claim the architecture exists to support. */}
      <p className="mt-2 text-xs text-zinc-400">
        {isAi
          ? "Written from this month's model output only — the language model never sees raw transactions."
          : "Generated from model output by deterministic rules — no language model was used."}
      </p>
    </Card>
  );
}
