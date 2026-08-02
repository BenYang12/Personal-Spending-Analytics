import type { Anomalies } from "@/lib/api";
import { categoryLabel, fullDate, money } from "@/lib/format";
import { Card, Empty, StaleBadge } from "./Card";

/**
 * Charges the anomaly model flagged.
 *
 * TONE IS A DELIBERATE PRODUCT DECISION HERE. My Isolation Forest runs at 0.645
 * precision — roughly one in three flags is a false positive. Styling these as
 * red fraud alerts would misrepresent what the model actually knows, and would
 * train the user to distrust the feature the first time it flags their normal
 * grocery run.
 *
 * So: amber not red, "worth reviewing" not "suspicious activity", and every row
 * states WHY it was flagged so the user can dismiss it themselves in two
 * seconds. The model surfaces candidates; the human decides.
 *
 * Accessibility: flagged status is never conveyed by colour alone — each row
 * carries an icon AND the reason text.
 */
export default function AnomalyList({
  anomalies,
  month,
}: {
  anomalies: Anomalies | null;
  month: string;
}) {
  if (!anomalies) {
    return (
      <Card id="anomalies" title="Unusual charges">
        <Empty>Could not load flagged charges.</Empty>
      </Card>
    );
  }

  // The endpoint returns every flagged charge for the account; the dashboard is
  // month-scoped, so filter to the month in view.
  const forMonth = anomalies.flaggedTransactions
    .filter((charge) => charge.postedDate.startsWith(month))
    .sort((a, b) => b.anomalyScore - a.anomalyScore);

  return (
    <Card
      id="anomalies"
      title="Unusual charges"
      subtitle={
        forMonth.length === 0
          ? "Nothing looked unusual this month"
          : `${forMonth.length} ${
              forMonth.length === 1 ? "charge" : "charges"
            } worth reviewing`
      }
      badge={anomalies.stale ? <StaleBadge label="Showing last known results" /> : undefined}
    >
      {forMonth.length === 0 ? (
        <Empty>
          Every charge this month matched your usual spending pattern.
        </Empty>
      ) : (
        <>
          <ul className="space-y-3">
            {forMonth.map((charge) => (
              <li
                key={charge.transactionId}
                className="rounded-lg border border-amber-200 bg-amber-50/60 p-3"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-2.5">
                    {/* Icon + text, never colour alone. aria-hidden because the
                        adjacent "Flagged" label already says it. */}
                    <span aria-hidden="true" className="mt-0.5 text-amber-700">
                      ⚠
                    </span>
                    <div>
                      <p className="font-medium text-zinc-900">{charge.merchant}</p>
                      <p className="text-xs text-zinc-600">
                        <span className="sr-only">Flagged. </span>
                        {fullDate(charge.postedDate)} ·{" "}
                        {categoryLabel(charge.category)}
                      </p>
                    </div>
                  </div>
                  <p className="shrink-0 tabular-nums font-semibold text-zinc-900">
                    {money(charge.amount)}
                  </p>
                </div>
                <p className="mt-2 pl-6 text-xs text-zinc-600">
                  Anomaly score {charge.anomalyScore.toFixed(2)} — flagged because
                  this charge sits outside your usual pattern for this account.
                </p>
              </li>
            ))}
          </ul>

          {/* Honesty footnote. The model's precision is public in my README, and
              hiding it from the person acting on its output would be the wrong
              way round. It also sets expectations so a false positive reads as
              "expected behaviour" rather than "this feature is broken". */}
          <p className="mt-4 border-t border-zinc-100 pt-3 text-xs text-zinc-500">
            Flagged by an Isolation Forest scored against this account&apos;s own
            history. Detection runs at 0.65 precision — some flags will be normal
            spending.
          </p>
        </>
      )}
    </Card>
  );
}
