/**
 * Display formatters.
 *
 * All money and dates go through here. Hand-rolled `$${n.toFixed(2)}` is the
 * kind of thing that looks fine on $15.49 and then prints "$1899" without a
 * separator on the number that actually matters — Intl handles grouping,
 * rounding, and locale rules that I'd otherwise get subtly wrong.
 */

const currency = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Whole dollars — for axis labels and totals where cents are noise. */
const currencyCompact = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  maximumFractionDigits: 0,
});

export const money = (amount: number) => currency.format(amount);
export const moneyShort = (amount: number) => currencyCompact.format(amount);

export const percent = (fraction: number) => `${Math.round(fraction * 100)}%`;

/** "2026-05-17" → "May 17, 2026". Never a locale-ambiguous 5/17 vs 17/5. */
export function fullDate(isoDate: string): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  });
}

/** "2026-05" → "May 2026" for headings and pickers. */
export function monthLabel(month: string): string {
  const [year, monthNumber] = month.split("-").map(Number);
  return new Date(Date.UTC(year, monthNumber - 1, 1)).toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    timeZone: "UTC",
  });
}

/** "2026-05" → "May" — for dense chart labels where the year repeats. */
export function monthShort(month: string): string {
  const [year, monthNumber] = month.split("-").map(Number);
  return new Date(Date.UTC(year, monthNumber - 1, 1)).toLocaleDateString("en-US", {
    month: "short",
    timeZone: "UTC",
  });
}

/**
 * A feature name from the ML pipeline, made readable.
 * "weekend_ratio" → "Weekend spending", "share_dining" → "Dining share".
 *
 * The model's vocabulary is snake_case and abbreviated; a user reading their
 * own dashboard should not have to decode it.
 */
export function featureLabel(feature: string): string {
  const labels: Record<string, string> = {
    txn_count: "Number of purchases",
    avg_ticket: "Typical purchase size",
    ticket_variability: "Purchase size spread",
    merchant_diversity: "Variety of merchants",
    weekend_ratio: "Weekend spending",
    recurring_share: "Subscriptions share",
    fixed_share: "Fixed costs share",
    spend_to_income: "Spending vs income",
  };
  if (labels[feature]) return labels[feature];

  // Category shares follow a predictable pattern, so derive rather than list.
  if (feature.startsWith("share_")) {
    const category = feature.slice("share_".length).replace(/_/g, " ");
    return `${category.charAt(0).toUpperCase()}${category.slice(1)} share`;
  }
  return feature.replace(/_/g, " ");
}

/**
 * Whether a feature reads naturally as a percentage.
 *
 * Ratios and shares are 0–1 in the model and should render as "78%"; counts and
 * dollar amounts must not. Getting this backwards would print "2455%" for a
 * typical purchase size of $24.55.
 */
const isRatioFeature = (feature: string) =>
  feature.endsWith("_ratio") ||
  feature.endsWith("_share") ||
  feature.startsWith("share_");

/** Format a feature value using the right unit for that feature. */
export function featureValue(feature: string, value: number): string {
  if (isRatioFeature(feature)) return percent(value);
  if (feature === "avg_ticket") return money(value);
  if (feature === "txn_count") return String(Math.round(value));
  return value.toFixed(2);
}

/** Category slugs from the pipeline ("food_and_drink") → "Food and drink". */
export function categoryLabel(category: string): string {
  const words = category.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
