import type { Subscriptions as SubscriptionsData } from "@/lib/api";
import { money } from "@/lib/format";
import { Card, Empty } from "./Card";

/**
 * Detected recurring charges.
 *
 * The annual total is deliberately the headline. "$15.49/month" is easy to
 * ignore; "$665/year" is the number that actually prompts someone to cancel
 * something. Same data, different framing, completely different response — the
 * per-item monthly figure is still there for anyone who wants it.
 *
 * These are DETECTED, not declared: found by pattern-matching (merchant, amount,
 * cadence) across transaction history, not from any subscription list a bank
 * hands over. The subtitle says so, because a user seeing a merchant they don't
 * consider a subscription deserves to know how it got there.
 */
export default function Subscriptions({ data }: { data: SubscriptionsData | null }) {
  if (!data) {
    return (
      <Card id="subscriptions" title="Recurring charges">
        <Empty>Could not load subscriptions.</Empty>
      </Card>
    );
  }

  if (data.subscriptions.length === 0) {
    return (
      <Card id="subscriptions" title="Recurring charges">
        <Empty>No recurring charges detected on this account.</Empty>
      </Card>
    );
  }

  return (
    <Card
      id="subscriptions"
      title="Recurring charges"
      subtitle="Detected from repeating merchant, amount, and monthly cadence"
    >
      <p className="mb-4 flex items-baseline gap-2">
        <span className="text-2xl font-semibold tabular-nums tracking-tight text-zinc-900">
          {money(data.annualTotal)}
        </span>
        <span className="text-sm text-zinc-600">
          per year · {money(data.monthlyTotal)} per month
        </span>
      </p>

      <table className="w-full text-sm">
        <caption className="sr-only">
          Recurring charges, largest monthly amount first
        </caption>
        <thead>
          <tr className="border-b border-zinc-200 text-left text-xs uppercase tracking-wide text-zinc-500">
            <th scope="col" className="pb-2 font-medium">
              Merchant
            </th>
            <th scope="col" className="pb-2 text-right font-medium">
              Monthly
            </th>
            <th scope="col" className="pb-2 text-right font-medium">
              Yearly
            </th>
            <th scope="col" className="pb-2 text-right font-medium">
              Seen
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100">
          {data.subscriptions.map((subscription) => (
            <tr key={subscription.merchant}>
              <th scope="row" className="py-2.5 text-left font-normal text-zinc-900">
                {subscription.merchant}
              </th>
              <td className="py-2.5 text-right tabular-nums text-zinc-900">
                {money(subscription.typicalAmount)}
              </td>
              <td className="py-2.5 text-right tabular-nums text-zinc-600">
                {money(subscription.annualCost)}
              </td>
              <td className="py-2.5 text-right tabular-nums text-zinc-500">
                {subscription.monthsSeen} mo
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
