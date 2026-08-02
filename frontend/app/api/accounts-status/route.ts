import { getAccounts } from "@/lib/api";

/**
 * Is a Plaid sync still running?
 *
 * The Link flow polls this after triggering a refresh. It exists because the
 * browser can't call my backend directly (no API key there), and because
 * `router.refresh()` in a loop would re-render the whole dashboard every two
 * seconds just to read one boolean.
 *
 * It returns ONLY a boolean — not the accounts themselves. A polling endpoint
 * should expose the minimum needed to answer its question; shipping full
 * account objects here would widen the surface for no reason.
 */
export async function GET() {
  const accounts = await getAccounts();

  return Response.json({
    syncing: (accounts ?? []).some((account) => account.syncStatus === "SYNCING"),
  });
}
