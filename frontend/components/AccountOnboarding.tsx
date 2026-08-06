import type { Account } from "@/lib/api";
import LinkAccount from "@/components/LinkAccount";

const MIN_TRANSACTIONS = 15;

export default function AccountOnboarding({
  connectedAccounts,
}: {
  connectedAccounts: Account[];
}) {
  const hasConnectedAccount = connectedAccounts.length > 0;
  const importedTransactions = connectedAccounts.reduce(
    (total, account) => total + account.transactionCount,
    0,
  );
  const isSyncing = connectedAccounts.some(
    (account) => account.syncStatus === "SYNCING",
  );

  return (
    <section className="mx-auto grid min-h-[calc(100vh-8rem)] max-w-5xl items-center gap-12 py-12 lg:grid-cols-[1.08fr_0.92fr] lg:py-20">
      <div className="max-w-2xl">
        <h2 className="max-w-xl text-balance text-4xl font-semibold tracking-[-0.035em] text-zinc-950 sm:text-5xl">
          See the patterns behind your spending.
        </h2>
        <p className="mt-5 max-w-xl text-pretty text-base leading-7 text-zinc-600 sm:text-lg">
          Connect an account to uncover behavioral trends, recurring charges,
          and transactions worth a second look.
        </p>

        <div className="mt-8">
          <LinkAccount prominent />
        </div>

        <div className="mt-6 flex max-w-lg items-start gap-3 text-sm leading-6 text-zinc-600">
          <LockIcon />
          <p>
            Plaid handles bank credentials directly. Ledger Lens receives
            transaction data, never your banking password.
          </p>
        </div>
      </div>

      <div className="relative overflow-hidden rounded-2xl border border-zinc-200 bg-white p-7 shadow-[0_20px_60px_-32px_rgba(24,24,27,0.35)] sm:p-9">
        <div className="absolute right-0 top-0 h-28 w-28 rounded-bl-full bg-amber-100/70" />
        {hasConnectedAccount ? (
          <>
            <div className="relative flex h-11 w-11 items-center justify-center rounded-full bg-amber-100 text-amber-900">
              <ActivityIcon />
            </div>
            <h3 className="relative mt-6 text-xl font-semibold tracking-tight text-zinc-950">
              {isSyncing ? "Importing your transactions" : "Account connected"}
            </h3>
            <p className="relative mt-3 leading-7 text-zinc-600">
              {isSyncing
                ? "Plaid is sending your transaction history. This page will update when the import finishes."
                : `We imported ${importedTransactions} transaction${importedTransactions === 1 ? "" : "s"}. Ledger Lens needs at least ${MIN_TRANSACTIONS} in one account before its behavioral analysis is meaningful.`}
            </p>
            <p className="relative mt-6 border-t border-zinc-200 pt-5 text-sm leading-6 text-zinc-500">
              {isSyncing
                ? "Importing usually takes less than a minute."
                : "Connect another account or add more sandbox history, then refresh."}
            </p>
          </>
        ) : (
          <>
            <div className="relative flex h-11 w-11 items-center justify-center rounded-full bg-zinc-900 text-white">
              <LensIcon />
            </div>
            <h3 className="relative mt-6 text-xl font-semibold tracking-tight text-zinc-950">
              What you’ll discover
            </h3>
            <ul className="relative mt-5 space-y-4 text-sm leading-6 text-zinc-600">
              <Benefit>How this month compares with your usual behavior</Benefit>
              <Benefit>Subscriptions and their estimated annual cost</Benefit>
              <Benefit>Unusual charges surfaced for your review</Benefit>
            </ul>
            <p className="relative mt-6 border-t border-zinc-200 pt-5 text-xs leading-5 text-zinc-500">
              Portfolio preview uses Plaid Sandbox and simulated bank data. Do
              not enter real banking credentials.
            </p>
          </>
        )}
      </div>
    </section>
  );
}

function Benefit({ children }: { children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-3">
      <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" />
      <span>{children}</span>
    </li>
  );
}

function LockIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="mt-1 h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8">
      <rect x="5" y="10" width="14" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

function ActivityIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 12h3l2-5 4 10 2-5h5" />
    </svg>
  );
}

function LensIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <circle cx="10.5" cy="10.5" r="5.5" />
      <path d="m15 15 5 5" />
      <path d="M8 11.5 10 9l2 2 2-3" />
    </svg>
  );
}
