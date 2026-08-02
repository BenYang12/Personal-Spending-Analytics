/**
 * The only proxy route in this app.
 *
 * Server Components fetch the backend directly — they run on the server, so the
 * API key never travels to the browser and there's nothing to proxy. But the
 * Plaid Link flow is inherently client-side: the modal runs in the browser and
 * hands back a public_token that has to be exchanged immediately. Those calls
 * originate from the browser, so they need a server-side hop to attach the key.
 *
 * A catch-all (`[...path]`) rather than one file per endpoint: all the Plaid
 * mutations have identical handling — forward the body, attach the key, return
 * the response — so separate files would be copies of the same six lines.
 *
 * SECURITY: the allowlist below is the important part. Without it this route
 * would forward ANY path a browser asked for, with my API key attached — a
 * confused-deputy hole that turns the proxy into an open gateway to every
 * endpoint I have, including the ones the browser is never meant to reach.
 */

const BASE_URL = process.env.LEDGERLENS_API_URL ?? "http://localhost:8080";
const API_KEY = process.env.LEDGERLENS_API_KEY ?? "";

// Exactly the paths the Plaid Link flow needs. Anything else is rejected.
const ALLOWED = new Set([
  "link-token",
  "exchange",
  "refresh",
  "sandbox-public-token",
]);

export async function POST(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  // params is a Promise in Next 16 — awaiting it is required, not optional.
  const { path } = await context.params;
  const endpoint = path.join("/");

  if (!ALLOWED.has(endpoint)) {
    return Response.json(
      { error: `Not a permitted Plaid endpoint: ${endpoint}` },
      { status: 403 },
    );
  }

  // Read the body as text and forward it verbatim. Parsing and re-serialising
  // would risk changing it; the backend already validates the shape.
  const body = await request.text();

  try {
    const response = await fetch(`${BASE_URL}/api/plaid/${endpoint}`, {
      method: "POST",
      headers: {
        "X-API-KEY": API_KEY,
        "Content-Type": "application/json",
      },
      body: body || undefined,
      cache: "no-store",
    });

    const text = await response.text();
    return new Response(text, {
      status: response.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    console.error(`Plaid proxy ${endpoint} failed:`, error);
    return Response.json(
      { error: "Could not reach the backend" },
      { status: 502 },
    );
  }
}
