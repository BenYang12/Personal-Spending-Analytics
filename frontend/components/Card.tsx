import type { ReactNode } from "react";

/**
 * The one layout primitive this dashboard needs.
 *
 * Every panel is a titled card, so rather than repeating the same border /
 * padding / heading markup seven times, it lives here once. This is also where
 * the accessibility structure is centralised: each card is a <section> whose
 * heading is wired up with aria-labelledby, so a screen reader announces
 * "Unusual charges, region" instead of dropping the user into unlabelled divs.
 *
 * Adding a component library for this would have meant ~20 generated files to
 * get a styled box.
 */
export function Card({
  id,
  title,
  subtitle,
  badge,
  children,
}: {
  id: string;
  title: string;
  subtitle?: string;
  badge?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section
      aria-labelledby={`${id}-heading`}
      className="rounded-xl border border-zinc-200 bg-white p-5 shadow-sm"
    >
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <h2
            id={`${id}-heading`}
            className="text-xs font-semibold uppercase tracking-wider text-zinc-500"
          >
            {title}
          </h2>
          {subtitle && <p className="mt-1 text-sm text-zinc-600">{subtitle}</p>}
        </div>
        {badge}
      </div>
      {children}
    </section>
  );
}

/**
 * The stale / fallback indicator.
 *
 * This little component is the visible payoff of the backend's graceful
 * degradation. When the scoring service is down the dashboard still renders
 * real numbers — this is what stops that from being a lie by omission.
 *
 * Amber rather than red, and it always carries TEXT: colour alone would be
 * invisible to a colourblind user and to anyone using a screen reader.
 */
export function StaleBadge({ label }: { label: string }) {
  return (
    <span
      className="shrink-0 rounded-full border border-amber-300 bg-amber-50 px-2.5 py-1
                 text-xs font-medium text-amber-900"
    >
      {label}
    </span>
  );
}

/** A neutral pill for model version, advice source, and similar metadata. */
export function Pill({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "accent";
}) {
  const tones = {
    neutral: "border-zinc-300 bg-zinc-50 text-zinc-700",
    accent: "border-teal-300 bg-teal-50 text-teal-900",
  };
  return (
    <span
      className={`shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium ${tones[tone]}`}
    >
      {children}
    </span>
  );
}

/** Consistent copy for "there's nothing here", so empty states never look broken. */
export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-zinc-500">{children}</p>;
}
