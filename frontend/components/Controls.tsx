"use client";

import { useRouter } from "next/navigation";
import type { Account } from "@/lib/api";
import { monthLabel } from "@/lib/format";

/**
 * Account and month pickers.
 *
 * One of only two client components in the app. It has to be — a <select> needs
 * an onChange handler, which can't exist on the server.
 *
 * The state lives in the URL, not in React. Changing a picker pushes a new
 * searchParams and the server re-renders the page with fresh data. That means
 * no client state to keep in sync, selections are shareable and survive a
 * refresh, and the back button works the way a user expects.
 *
 * Both controls are native <select> elements on purpose. A custom dropdown
 * would need keyboard handling, focus management, and ARIA roles rewritten from
 * scratch — all of which the browser already does correctly, and better on
 * mobile.
 */
export default function Controls({
  accounts,
  selectedAccountId,
  selectedMonth,
}: {
  accounts: Account[];
  selectedAccountId: number;
  selectedMonth: string;
}) {
  const router = useRouter();

  const selected = accounts.find((a) => a.id === selectedAccountId);
  const months = selected?.months ?? [];

  function go(accountId: number, month: string) {
    router.push(`/?account=${accountId}&month=${month}`);
  }

  function onAccountChange(accountId: number) {
    // Switching accounts can strand the current month — account 3 may have no
    // data for a month account 2 does. Falling back to the new account's most
    // recent month keeps the user out of an empty view they didn't ask for.
    const next = accounts.find((a) => a.id === accountId);
    const month =
      next?.months.includes(selectedMonth) ? selectedMonth : next?.months[0];
    if (month) go(accountId, month);
  }

  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="flex flex-col gap-1">
        <label
          htmlFor="account"
          className="text-xs font-medium uppercase tracking-wide text-zinc-500"
        >
          Account
        </label>
        <select
          id="account"
          value={selectedAccountId}
          onChange={(event) => onAccountChange(Number(event.target.value))}
          className="rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm
                     focus:border-zinc-900 focus:outline-none focus:ring-2 focus:ring-zinc-900/10"
        >
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name} ({account.transactionCount})
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="month"
          className="text-xs font-medium uppercase tracking-wide text-zinc-500"
        >
          Month
        </label>
        <select
          id="month"
          value={selectedMonth}
          onChange={(event) => go(selectedAccountId, event.target.value)}
          className="rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm
                     focus:border-zinc-900 focus:outline-none focus:ring-2 focus:ring-zinc-900/10"
        >
          {/* Only months with data — so no selection can land on an empty view. */}
          {months.map((month) => (
            <option key={month} value={month}>
              {monthLabel(month)}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
