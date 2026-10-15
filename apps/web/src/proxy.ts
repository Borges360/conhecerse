import { NextRequest, NextResponse } from "next/server";
export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const connect = new Set<string>(["'self'"]);
  for (const key of [
    "LIVEKIT_URL",
    "NEXT_PUBLIC_LIVEKIT_URL",
    "S3_PUBLIC_ENDPOINT",
  ]) {
    const value = process.env[key];
    if (value) {
      try {
        const url = new URL(value);
        connect.add(url.origin);
      } catch {}
    }
  }
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "connect-src " + [...connect].join(" "),
    "media-src 'self' blob:",
    "worker-src 'self' blob:",
    "object-src 'none'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join("; ");
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  if (/^\/(app|portal|sessao)(\/|$)/.test(request.nextUrl.pathname))
    response.headers.set("Cache-Control", "private, no-store");
  return response;
}
export const config = {
  matcher: [
    "/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:png|svg|woff2)$).*)",
  ],
};
