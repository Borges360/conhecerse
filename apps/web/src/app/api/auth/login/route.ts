import { NextRequest, NextResponse } from "next/server";
import * as oidc from "openid-client";
import { configuration, seal, cookieOptions, origin } from "@/lib/session";
export const dynamic = "force-dynamic";
export async function GET(request: NextRequest) {
  try {
    const cfg = await configuration();
    const verifier = oidc.randomPKCECodeVerifier(),
      challenge = await oidc.calculatePKCECodeChallenge(verifier),
      state = oidc.randomState(),
      nonce = oidc.randomNonce();
    const next = request.nextUrl.searchParams.get("next") || "/app";
    const returnTo =
      next.startsWith("/") && !next.startsWith("//") && !next.includes("\\")
        ? next
        : "/app";
    const url = oidc.buildAuthorizationUrl(cfg, {
      redirect_uri: origin() + "/api/auth/callback",
      scope: "openid profile email",
      code_challenge: challenge,
      code_challenge_method: "S256",
      state,
      nonce,
      acr_values: "2",
      ...(request.nextUrl.searchParams.get("reauth") === "true"
        ? { max_age: "0", prompt: "login" }
        : {}),
    });
    const response = NextResponse.redirect(url);
    response.cookies.set(
      "psico-oidc",
      seal({ verifier, state, nonce, returnTo }).toString("base64url"),
      { ...cookieOptions(), maxAge: 600 },
    );
    return response;
  } catch {
    return NextResponse.redirect(
      new URL("/login?error=configuracao", origin()),
    );
  }
}
