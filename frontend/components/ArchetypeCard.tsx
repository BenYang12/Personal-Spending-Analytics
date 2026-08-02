import type { Archetype } from "@/lib/api";
import { featureLabel, featureValue } from "@/lib/format";
import { Card, Empty, Pill, StaleBadge } from "./Card";

/**
 * The clustering model's verdict, plus the evidence for it.
 *
 * The evidence is the whole point. "You're a Weekend Spender" is an assertion a
 * user has no reason to trust; "78% of your spending was Fri–Sun, versus 42%
 * typical" is a claim they can check against their own memory. Showing the
 * numbers behind an unsupervised model's label is what separates it from
 * horoscope output.
 *
 * Each bar shows this month against the population average, so the comparison
 * is visual as well as numeric.
 */
export default function ArchetypeCard({ archetype }: { archetype: Archetype | null }) {
  if (!archetype) {
    return (
      <Card id="archetype" title="Spending archetype">
        <Empty>Could not load the archetype.</Empty>
      </Card>
    );
  }

  // No archetype means either a thin month or an unavailable scorer — the note
  // from the backend explains which, so I surface it verbatim rather than
  // inventing my own message.
  if (!archetype.archetype) {
    return (
      <Card
        id="archetype"
        title="Spending archetype"
        badge={archetype.stale ? <StaleBadge label="Scoring unavailable" /> : undefined}
      >
        <Empty>{archetype.note ?? "No archetype for this month."}</Empty>
      </Card>
    );
  }

  return (
    <Card
      id="archetype"
      title="Spending archetype"
      badge={
        archetype.stale ? (
          <StaleBadge label="Showing last known result" />
        ) : (
          <Pill>{archetype.modelVersion ?? "model"}</Pill>
        )
      }
    >
      <p className="text-2xl font-semibold tracking-tight text-zinc-900">
        {archetype.archetype}
      </p>
      {archetype.description && (
        <p className="mt-1 text-sm text-zinc-600">{archetype.description}</p>
      )}

      {archetype.evidence.length > 0 && (
        <div className="mt-5">
          <h3 className="mb-3 text-xs font-medium uppercase tracking-wide text-zinc-500">
            Why this month landed here
          </h3>
          <ul className="space-y-3">
            {archetype.evidence.map((item) => (
              <EvidenceRow key={item.feature} evidence={item} />
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}

function EvidenceRow({
  evidence,
}: {
  evidence: Archetype["evidence"][number];
}) {
  // Bars are scaled against whichever value is larger, so the taller bar always
  // fills the track and the comparison stays legible whatever the units.
  const scale = Math.max(evidence.yourValue, evidence.populationAverage, 0.0001);
  const yourWidth = Math.min(100, (evidence.yourValue / scale) * 100);
  const averageWidth = Math.min(100, (evidence.populationAverage / scale) * 100);

  const above = evidence.stdDevsFromAverage > 0;

  return (
    <li>
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="text-zinc-700">{featureLabel(evidence.feature)}</span>
        <span className="tabular-nums font-medium text-zinc-900">
          {featureValue(evidence.feature, evidence.yourValue)}
        </span>
      </div>

      <div className="mt-1.5 space-y-1">
        <div className="h-2 overflow-hidden rounded-full bg-zinc-100">
          <div
            className="h-full rounded-full bg-teal-600"
            style={{ width: `${yourWidth}%` }}
          />
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-zinc-100">
          <div
            className="h-full rounded-full bg-zinc-300"
            style={{ width: `${averageWidth}%` }}
          />
        </div>
      </div>

      {/* The plain-language comparison. This sentence is what a screen reader
          conveys, and it's also what most sighted users actually read — the
          bars are decoration on top of it, not the other way round. */}
      <p className="mt-1 text-xs text-zinc-500">
        {above ? "Above" : "Below"} typical (
        {featureValue(evidence.feature, evidence.populationAverage)} average),{" "}
        {Math.abs(evidence.stdDevsFromAverage).toFixed(1)} standard deviations{" "}
        {above ? "higher" : "lower"}
      </p>
    </li>
  );
}
