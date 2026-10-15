import { NextResponse } from "next/server";
import {
  session,
  pool,
  checkCsrf,
  cookieName,
  configuration,
  origin,
} from "@/lib/session";
export async function POST(request: Request) {
  const current = await session();
  if (!current || !checkCsrf(request, current.csrfToken))
    return NextResponse.json({ code: "CSRF_INVALID" }, { status: 403 });
  await pool.query("delete from bff.sessions where id_hash=$1", [current.id]);
  // End the local BFF session immediately. OIDC session termination is delegated to the IdP endpoint.
  const cfg = await configuration();
  const endpoint = cfg.serverMetadata().end_session_endpoint;
  const logoutUrl = endpoint ? new URL(endpoint) : null;
  if (logoutUrl) {
    logoutUrl.searchParams.set("post_logout_redirect_uri", origin() + "/login");
    logoutUrl.searchParams.set(
      "client_id",
      process.env.OIDC_CLIENT_ID || "conhecerse-web",
    );
  }
  const result = NextResponse.json({
    logoutUrl: logoutUrl?.toString() || "/login",
  });
  result.cookies.delete(cookieName());
  return result;
}
