import { money, monthShort, moneyShort } from "@/lib/format";
import { Card, Empty } from "./Card";

/**
 * Six months of spending as vertical bars.
 *
 * The selected month is highlighted so the single-month panels above have
 * context — "is $2,004 a lot?" is only answerable against the other months.
 *
 * The <ul> carries an aria-label summarising the whole series, so a screen
 * reader gets the shape of the trend in one sentence instead of six list items
 * it has to hold in memory and compare.
 */
export default function SpendTrend({
  trend,
  selectedMonth,
}: {
  trend: { month: string; total: number }[];
  selectedMonth: string;
}) {
  const withData = trend.filter((point) => point.total > 0);

  if (withData.length === 0) {
    return (
      <Card id="trend" title="Spending trend">
        <Empty>Not enough history to show a trend.</Empty>
      </Card>
    );
  }

  const peak = withData.reduce((max, point) =>
    point.total > max.total ? point : max,
  );
  const average =
    withData.reduce((sum, point) => sum + point.total, 0) / withData.length;

  return (
    <Card
      id="trend"
      title="Spending trend"
      subtitle={`${money(average)} average over ${withData.length} months`}
    >
      <ul
        className="flex h-40 items-end gap-2"
        aria-label={`Monthly spending over ${withData.length} months, highest ${money(
          peak.total,
        )} in ${monthShort(peak.month)}, averaging ${money(average)}`}
      >
        {trend.map((point) => {
          const isSelected = point.month === selectedMonth;
          // Percentage heights against the peak, with a 2% floor so a very small
          // month still renders as a visible sliver rather than disappearing.
          const height = peak.total > 0 ? (point.total / peak.total) * 100 : 0;

          return (
            <li key={point.month} className="flex flex-1 flex-col items-center gap-1">
              <span className="text-[10px] tabular-nums text-zinc-500">
                {point.total > 0 ? moneyShort(point.total) : ""}
              </span>
              <div className="flex w-full flex-1 items-end">
                <div
                  className={`w-full rounded-t transition-colors ${
                    isSelected ? "bg-teal-600" : "bg-zinc-200"
                  }`}
                  style={{ height: `${Math.max(height, point.total > 0 ? 2 : 0)}%` }}
                />
              </div>
              <span
                className={`text-xs ${
                  isSelected ? "font-semibold text-zinc-900" : "text-zinc-500"
                }`}
              >
                {monthShort(point.month)}
              </span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
