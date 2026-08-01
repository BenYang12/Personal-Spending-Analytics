import { apiKeyPresent, getAccounts } from "@/lib/api";

// A Server Component: this function body runs on the Next server only. The
// fetch inside getAccounts() carries my API key, and none of that reaches the
// browser — the client receives finished HTML.
//
// Step 23 checkpoint only. The real dashboard replaces this in Step 25.
export default async function Home() {
  const accounts = await getAccounts();

  return (
    <main className="mx-auto max-w-3xl px-6 py-16">
      <h1 className="text-2xl font-semibold tracking-tight">Ledger Lens</h1>
      <p className="mt-2 text-sm text-zinc-600">
        Step 23 checkpoint — accounts fetched server-side.
      </p>

      {!apiKeyPresent() && (
        <p className="mt-6 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          No LEDGERLENS_API_KEY set. Copy <code>.env.example</code> to{" "}
          <code>.env.local</code>.
        </p>
      )}

      {accounts === null ? (
        <p className="mt-6 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-900">
          Could not reach the backend. Is it running on :8080?
        </p>
      ) : (
        <ul className="mt-6 divide-y divide-zinc-200 rounded border border-zinc-200">
          {accounts.map((account) => (
            <li key={account.id} className="flex justify-between px-4 py-3 text-sm">
              <span>{account.name}</span>
              <span className="text-zinc-500">
                {account.type} · {account.syncStatus}
              </span>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
