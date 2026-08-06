"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { usePlaidLink } from "react-plaid-link";

/**
 * The live Plaid Link flow.
 *
 * Four steps, three of them invisible to the user:
 *   1. Ask my backend for a link_token (short-lived, safe in the browser).
 *   2. Open Plaid's modal with it. The user's bank credentials go to PLAID,
 *      never to me — that's the entire point of Link, and why this flow exists
 *      rather than a username/password form of my own.
 *   3. Plaid returns a public_token. Exchange it server-side for a long-lived
 *      access_token, which is stored in Postgres and never returned to a browser.
 *   4. Kick off an async sync and poll until the account leaves SYNCING.
 *
 * Sandbox credentials for the demo: user_good / pass_good.
 */

type Status = "idle" | "preparing" | "exchanging" | "syncing" | "done" | "error";

export default function LinkAccount({ prominent = false }: { prominent?: boolean }) {
  const router = useRouter();
  const [linkToken, setLinkToken] = useState<string | null>(null);
  const [status, setStatus] = useState<Status>("idle");
  const [message, setMessage] = useState<string | null>(null);

  // Fetch a link token up front so the modal opens instantly on click. Doing it
  // inside the click handler would add a round trip between the click and the
  // modal, which reads as an unresponsive button.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const response = await fetch("/api/plaid/link-token", { method: "POST" });
        if (!response.ok) throw new Error(`link-token ${response.status}`);
        const data = await response.json();
        // Guard against setting state after unmount — React warns otherwise,
        // and in dev StrictMode this effect runs twice.
        if (!cancelled) setLinkToken(data.linkToken);
      } catch {
        if (!cancelled) {
          setStatus("error");
          setMessage("Could not reach Plaid. Are PLAID_* credentials set?");
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const onSuccess = useCallback(
    async (publicToken: string) => {
      try {
        setStatus("exchanging");
        const exchange = await fetch("/api/plaid/exchange", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ publicToken }),
        });
        if (!exchange.ok) throw new Error(`exchange ${exchange.status}`);
        const { linked } = await exchange.json();

        // Ingestion is async and returns 202 immediately — the UI must not
        // block on it, which is exactly why the backend was built this way.
        setStatus("syncing");
        setMessage(`Linked ${linked} accounts. Importing transactions…`);
        await fetch("/api/plaid/refresh", { method: "POST" });

        // Poll for completion. There's no push channel (webhooks were cut for
        // scope), so the honest options are polling or making the user refresh.
        // Capped at ~30s so a stuck sync surfaces instead of spinning forever.
        for (let attempt = 0; attempt < 15; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 2000));
          const accounts = await fetch("/api/accounts-status").then((r) =>
            r.ok ? r.json() : null,
          );
          if (accounts && !accounts.syncing) break;
        }

        setStatus("done");
        setMessage("Import complete.");
        // Re-run the Server Component so the new accounts appear.
        router.refresh();
      } catch (error) {
        console.error(error);
        setStatus("error");
        setMessage("Something went wrong linking the account.");
      }
    },
    [router],
  );

  const { open, ready } = usePlaidLink({
    token: linkToken,
    // Plaid types public_token as `string | null` — it can be null in edge cases
    // like an update-mode link that needed no re-auth. Exchanging a null token
    // would fail confusingly downstream, so it's caught here.
    onSuccess: (publicToken) => {
      if (!publicToken) {
        setStatus("error");
        setMessage("Plaid returned no token. Nothing was linked.");
        return;
      }
      void onSuccess(publicToken);
    },
  });

  const busy =
    status === "exchanging" || status === "syncing" || status === "preparing";

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={() => open()}
        disabled={!ready || !linkToken || busy}
        className={`rounded-md bg-zinc-900 font-medium text-white transition
                   hover:bg-zinc-700 focus:outline-none focus:ring-2
                   focus:ring-zinc-900 focus:ring-offset-2
                   disabled:cursor-not-allowed disabled:bg-zinc-300
                   ${prominent ? "px-6 py-3 text-base" : "px-4 py-2 text-sm"}`}
      >
        {busy
          ? "Connecting…"
          : prominent
            ? "Connect securely with Plaid"
            : "Link account"}
      </button>

      {/* aria-live so the status change is announced, not just shown. A sighted
          user sees the text appear; a screen-reader user would otherwise get
          nothing at all during a 30-second sync. */}
      <p
        aria-live="polite"
        className={`text-xs ${
          status === "error" ? "text-red-700" : "text-zinc-500"
        }`}
      >
        {message ?? " "}
      </p>
    </div>
  );
}
