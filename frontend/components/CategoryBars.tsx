import type { CategorySummary } from "@/lib/api";
import { categoryLabel, money, percent } from "@/lib/format";
import { Card, Empty } from "./Card";

/**
 * Where the money went, as a bar per category.
 *
 * Built as a <table> with bars drawn INSIDE the cells, rather than a chart with
 * a separate table bolted on for screen readers. One structure serves everyone:
 * the numbers are always visible (which is what people want anyway), the bars
 * are a visual read of the same data, and there's no hidden second copy that
 * can silently drift from what's on screen.
 *
 * No charting library. This is ~40 lines of CSS-width arithmetic; Recharts would
 * be ~500KB and would need its accessibility added back by hand.
 */
export default function CategoryBars({
  summary,
  month,
}: {
  summary: CategorySummary[] | null;
  month: string;
}) {
  if (!summary) {
    return (
      <Card id="categories" title="Where it went">
        <Empty>Could not load spending.</Empty>
      </Card>
    );
  }

  // Outflows only. Income is negative under Plaid's convention, and including it
  // would make the percentages meaningless (and could make them exceed 100%).
  const spending = summary
    .filter((row) => row.total > 0)
    .sort((a, b) => b.total - a.total);

  if (spending.length === 0) {
    return (
      <Card id="categories" title="Where it went">
        <Empty>No spending recorded this month.</Empty>
      </Card>
    );
  }

  const total = spending.reduce((sum, row) => sum + row.total, 0);
  const largest = spending[0].total;

  return (
    <Card
      id="categories"
      title="Where it went"
      subtitle={`${money(total)} across ${spending.length} categories`}
    >
      <table className="w-full text-sm">
        <caption className="sr-only">
          Spending by category for {month}, largest first
        </caption>
        <thead className="sr-only">
          <tr>
            <th scope="col">Category</th>
            <th scope="col">Amount</th>
            <th scope="col">Share of spending</th>
          </tr>
        </thead>
        <tbody>
          {spending.map((row) => (
            <tr key={row.category}>
              <th
                scope="row"
                className="w-32 py-2 pr-3 text-left font-normal text-zinc-700"
              >
                {categoryLabel(row.category)}
              </th>
              <td className="py-2">
                {/* Bar width is relative to the LARGEST category, not to the
                    total — otherwise every bar in a well-spread month would be
                    a stub and the chart would say nothing. */}
                <div className="h-6 overflow-hidden rounded bg-zinc-100">
                  <div
                    className="h-full rounded bg-teal-600"
                    style={{ width: `${(row.total / largest) * 100}%` }}
                  />
                </div>
              </td>
              <td className="w-24 py-2 pl-3 text-right tabular-nums font-medium text-zinc-900">
                {money(row.total)}
              </td>
              <td className="w-12 py-2 pl-2 text-right tabular-nums text-zinc-500">
                {percent(row.total / total)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
