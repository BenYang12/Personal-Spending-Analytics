import AdvicePanel from "@/components/AdvicePanel";
import AnomalyList from "@/components/AnomalyList";
import ArchetypeCard from "@/components/ArchetypeCard";
import CategoryBars from "@/components/CategoryBars";
import Controls from "@/components/Controls";
import LinkAccount from "@/components/LinkAccount";
import SpendTrend from "@/components/SpendTrend";
import Subscriptions from "@/components/Subscriptions";
import {
  apiKeyPresent,
  getAccounts,
  getAdvice,
  getAnomalies,
  getArchetype,
  getCategorySummary,
  getSpendTrend,
  getSubscriptions,
} from "@/lib/api";
import { monthLabel } from "@/lib/format";

/**
 * The whole dashboard — one Server Component.
 *
 * Everything below runs on the Next server: the API key is used here and never
 * shipped to the browser, and the client receives finished HTML with the data
 * already in it. No loading spinners, no client-side fetch waterfall.
 *
 * Selection state lives in the URL (?account=2&month=2026-05), so changing a
 * picker is a server round-trip that re-renders with fresh data. That's why
 * there's no client state library here, and why every view is shareable.
 */

// Plaid's sandbox creates savings, CD, and mortgage accounts alongside the ones
// with real activity. Below this threshold a dashboard is technically correct
// and completely uninformative, so those accounts stay out of the picker.
const MIN_TRANSACTIONS = 15;

export default async function Dashboard({
  searchParams,
}: {
  // In Next 16 searchParams is a Promise and must be awaited — a breaking change
  // from earlier versions, where it was a plain object.
  searchParams: Promise<{ account?: string; month?: string }>;
}) {
  const params = await searchParams;

  if (!apiKeyPresent()) {
    return (
      <Shell>
        <Notice tone="warn">
          No <code>LEDGERLENS_API_KEY</code> set. Copy{" "}
          <code>frontend/.env.example</code> to <code>frontend/.env.local</code>.
        </Notice>
      </Shell>
    );
  }

  const allAccounts = await getAccounts();

  if (allAccounts === null) {
    return (
      <Shell>
        <Notice tone="error">
          Could not reach the backend on port 8080. Start it with{" "}
          <code>cd backend &amp;&amp; ./gradlew bootRun</code>.
        </Notice>
      </Shell>
    );
  }

  const accounts = allAccounts
    .filter((account) => account.transactionCount >= MIN_TRANSACTIONS)
    .sort((a, b) => b.transactionCount - a.transactionCount);

  if (accounts.length === 0) {
    return (
      <Shell action={<LinkAccount />}>
        <Notice tone="info">
          No accounts with enough transaction history yet. Link a bank account to
          get started — the Plaid sandbox accepts{" "}
          <code>user_good</code> / <code>pass_good</code>.
        </Notice>
      </Shell>
    );
  }

  // Default to the busiest account and its most recent month, so the first view
  // is always the most informative one rather than an arbitrary pick.
  const account =
    accounts.find((candidate) => String(candidate.id) === params.account) ??
    accounts[0];
  const month =
    account.months.find((candidate) => candidate === params.month) ??
    account.months[0];

  // One parallel batch. These are independent reads, so awaiting them in
  // sequence would stack six round trips for no reason.
  const [summary, archetype, anomalies, subscriptions, advice, trend] =
    await Promise.all([
      getCategorySummary(account.id, month),
      getArchetype(account.id, month),
      getAnomalies(account.id),
      getSubscriptions(account.id),
      getAdvice(account.id, month),
      getSpendTrend(account.id, month, 6),
    ]);

  return (
    <Shell action={<LinkAccount />}>
      <div className="mb-6">
        <Controls
          accounts={accounts}
          selectedAccountId={account.id}
          selectedMonth={month}
        />
      </div>

      <p className="mb-4 text-sm text-zinc-600">
        Showing <span className="font-medium text-zinc-900">{account.name}</span>{" "}
        for <span className="font-medium text-zinc-900">{monthLabel(month)}</span>
      </p>

      {/* Ordered deliberately: who you are → where the money went → what looks
          wrong → what to do about it. */}
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="lg:col-span-2">
          <ArchetypeCard archetype={archetype} />
        </div>
        <CategoryBars summary={summary} month={month} />
        <SpendTrend trend={trend} selectedMonth={month} />
        <AnomalyList anomalies={anomalies} month={month} />
        <Subscriptions data={subscriptions} />
        <div className="lg:col-span-2">
          <AdvicePanel advice={advice} />
        </div>
      </div>
    </Shell>
  );
}

function Shell({
  children,
  action,
}: {
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-zinc-50">
      <header className="border-b border-zinc-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight text-zinc-900">
              Ledger Lens
            </h1>
            <p className="text-xs text-zinc-500">
              Spending behaviour analysis · Plaid sandbox data
            </p>
          </div>
          {action}
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-6 py-6">{children}</main>
    </div>
  );
}

function Notice({
  children,
  tone,
}: {
  children: React.ReactNode;
  tone: "info" | "warn" | "error";
}) {
  const tones = {
    info: "border-zinc-300 bg-white text-zinc-700",
    warn: "border-amber-300 bg-amber-50 text-amber-900",
    error: "border-red-300 bg-red-50 text-red-900",
  };
  return (
    <div className={`rounded-lg border p-4 text-sm ${tones[tone]}`}>{children}</div>
  );
}
