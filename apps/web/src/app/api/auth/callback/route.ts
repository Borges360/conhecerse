import { NextRequest, NextResponse } from "next/server";
import * as oidc from "openid-client";
import { randomBytes } from "node:crypto";
import {
  configuration,
  unseal,
  seal,
  pool,
  hash,
  cookieName,
  cookieOptions,
  origin,
} from "@/lib/session";
export const dynamic = "force-dynamic";
export async function GET(request: NextRequest) {
  try {
    const raw = request.cookies.get("psico-oidc")?.value;
    if (!raw) throw new Error("OIDC_TRANSACTION_REQUIRED");
    const pending = unseal<{
      verifier: string;
      state: string;
      nonce: string;
      returnTo: string;
    }>(Buffer.from(raw, "base64url"));
    const tokens = await oidc.authorizationCodeGrant(
      await configuration(),
      new URL(origin() + "/api/auth/callback" + request.nextUrl.search),
      {
        pkceCodeVerifier: pending.verifier,
        expectedState: pending.state,
        expectedNonce: pending.nonce,
        idTokenExpected: true,
      },
    );
    const claims = tokens.claims();
    if (!claims?.sub) throw new Error("IDENTITY_REQUIRED");
    const rawSession = randomBytes(32).toString("base64url");
    const csrfToken = randomBytes(32).toString("base64url");
    await pool.query(
      "insert into bff.sessions(id_hash,encrypted_tokens,csrf_token,subject,expires_at) values($1,$2,$3,$4,now()+interval '8 hours')",
      [
        hash(rawSession),
        seal({
          accessToken: tokens.access_token,
          refreshToken: tokens.refresh_token,
          expiresAt: Date.now() + (tokens.expires_in || 120) * 1000,
          name: claims.name,
          subject: claims.sub,
        }),
        csrfToken,
        claims.sub,
      ],
    );
    const response = NextResponse.redirect(new URL(pending.returnTo, origin()));
    response.cookies.set(cookieName(), rawSession, cookieOptions());
    response.cookies.delete("psico-oidc");
    return response;
  } catch (error) {
    const diagnostic = error as {
      code?: string;
      name?: string;
      cause?: { error?: string };
    };
    const providerCode = /^[a-z_]{1,50}$/.test(diagnostic.cause?.error || "")
      ? diagnostic.cause?.error
      : undefined;
    console.error(
      JSON.stringify({
        event: "oidc_callback_failed",
        code: diagnostic.code || diagnostic.name || "AUTHENTICATION_FAILED",
        providerCode,
      }),
    );
    return NextResponse.redirect(
      new URL("/login?error=autenticacao", origin()),
    );
  }
}
