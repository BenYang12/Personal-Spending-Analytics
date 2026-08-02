/**
 * Server-side access to my Spring Boot API.
 *
 * Every function here runs ONLY on the Next server — in Server Components or
 * route handlers, never in the browser. That's what keeps LEDGERLENS_API_KEY
 * safe: it isn't hidden from the client, it's never sent there at all. Note the
 * env vars have no NEXT_PUBLIC_ prefix, which is precisely the mechanism that
 * would expose them.
 *
 * The types below mirror my backend's response records exactly. I write them by
 * hand rather than generating from OpenAPI because there are nine of them and a
 * generator is a build step I'd have to maintain — but the tradeoff is real:
 * these can drift, and nothing would tell me. `apiKeyPresent()` and the runtime
 * null handling are what keep a drift from becoming a blank page.
 */

const BASE_URL = process.env.LEDGERLENS_API_URL ?? "http://localhost:8080";
const API_KEY = process.env.LEDGERLENS_API_KEY ?? "";

// ---------------------------------------------------------------------------
// types — mirror the backend records
// ---------------------------------------------------------------------------

export type Account = {
  id: number;
  name: string;
  type: string;
  syncStatus: string;
  /** Total transactions — used to hide accounts too thin to be worth showing. */
  transactionCount: number;
  /** Months with data, newest first ("2026-05") — populates the month picker. */
  months: string[];
};

export type CategorySummary = {
  category: string;
  total: number;
  count: number;
};

export type Evidence = {
  feature: string;
  yourValue: number;
  populationAverage: number;
  stdDevsFromAverage: number;
};

export type Archetype = {
  accountId: number;
  month: string;
  archetype: string | null;
  description: string | null;
  cluster: number | null;
  distanceToCentroid: number | null;
  evidence: Evidence[];
  modelVersion: string | null;
  /** True when the scoring service was unreachable and this came from cache. */
  stale: boolean;
  note: string | null;
};

export type FlaggedTransaction = {
  transactionId: number;
  plaidTransactionId: string;
  postedDate: string;
  amount: number;
  merchant: string;
  category: string;
  anomalyScore: number;
  modelName: string;
  scoredAt: string;
};

export type Anomalies = {
  accountId: number;
  scored: number;
  flagged: number;
  flaggedTransactions: FlaggedTransaction[];
  modelVersion: string | null;
  stale: boolean;
  note: string | null;
};

export type Subscription = {
  merchant: string;
  category: string;
  typicalAmount: number;
  monthsSeen: number;
  firstSeen: string;
  lastSeen: string;
  annualCost: number;
};

export type Subscriptions = {
  subscriptions: Subscription[];
  monthlyTotal: number;
  annualTotal: number;
};

export type Recommendation = {
  title: string;
  detail: string;
  estimatedMonthlySaving: number;
};

export type Advice = {
  accountId: number;
  month: string;
  advice: {
    summary: string;
    recommendations: Recommendation[];
    flaggedChargeExplanation: string;
  } | null;
  /** "claude" | "claude-retry" | "rule-based" | "none" */
  source: string;
  cached: boolean;
  note: string | null;
};

// ---------------------------------------------------------------------------
// fetch helper
// ---------------------------------------------------------------------------

/**
 * Fetch from the backend, returning null instead of throwing.
 *
 * Deliberate: this dashboard's whole premise is that a downstream failure
 * degrades one panel rather than the page. If the backend is unreachable I want
 * an empty archetype card next to a working transaction list, not a Next error
 * boundary swallowing the entire route. Callers handle null explicitly.
 */
async function get<T>(path: string): Promise<T | null> {
  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      headers: { "X-API-KEY": API_KEY },
      // Financial data — never serve a cached page. Next 16 still honours
      // no-store; without it a month's figures could be minutes stale after a
      // sync, which is exactly the wrong thing to be casual about.
      cache: "no-store",
    });

    if (!response.ok) {
      console.error(`GET ${path} → ${response.status}`);
      return null;
    }
    return (await response.json()) as T;
  } catch (error) {
    // Connection refused when the backend isn't running. Logged server-side,
    // invisible to the user beyond the affected panel's empty state.
    console.error(`GET ${path} failed:`, error);
    return null;
  }
}

// ---------------------------------------------------------------------------
// endpoints
// ---------------------------------------------------------------------------

export const getAccounts = () => get<Account[]>("/api/accounts");

export const getCategorySummary = (accountId: number, month: string) =>
  get<CategorySummary[]>(`/api/summary?accountId=${accountId}&month=${month}`);

export const getArchetype = (accountId: number, month: string) =>
  get<Archetype>(`/api/scores/archetype?accountId=${accountId}&month=${month}`);

export const getAnomalies = (accountId: number) =>
  get<Anomalies>(`/api/scores/anomalies?accountId=${accountId}`);

export const getSubscriptions = (accountId: number) =>
  get<Subscriptions>(`/api/subscriptions?accountId=${accountId}`);

export const getAdvice = (accountId: number, month: string) =>
  get<Advice>(`/api/advice?accountId=${accountId}&month=${month}`);

/**
 * Spend totals for the last `count` months, oldest first — the trend chart.
 *
 * Six parallel calls rather than a new backend endpoint. Each is a fast indexed
 * aggregate and they run concurrently server-side, so the wall-clock cost is
 * roughly one call. Adding an endpoint for something six existing calls already
 * answer would be backend surface I'd have to test and maintain for no gain.
 */
export async function getSpendTrend(
  accountId: number,
  endMonth: string,
  count = 6,
): Promise<{ month: string; total: number }[]> {
  const months = recentMonths(endMonth, count);

  const results = await Promise.all(
    months.map(async (month) => {
      const summary = await getCategorySummary(accountId, month);
      // Outflows only. Including the paycheck would net the month to roughly
      // zero and make the whole chart meaningless.
      const total = (summary ?? [])
        .filter((row) => row.total > 0)
        .reduce((sum, row) => sum + row.total, 0);
      return { month, total };
    }),
  );
  return results;
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/** The `count` months ending at `endMonth` (YYYY-MM), oldest first. */
export function recentMonths(endMonth: string, count: number): string[] {
  const [year, month] = endMonth.split("-").map(Number);
  const months: string[] = [];

  for (let offset = count - 1; offset >= 0; offset--) {
    // Date handles year rollover; month is 0-indexed here, hence the -1.
    const date = new Date(Date.UTC(year, month - 1 - offset, 1));
    months.push(
      `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`,
    );
  }
  return months;
}

/** Surfaces a misconfigured .env.local as a clear message rather than a 401. */
export const apiKeyPresent = () => API_KEY.length > 0;
