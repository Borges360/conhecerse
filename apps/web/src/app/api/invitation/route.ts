import { NextRequest, NextResponse } from "next/server";
import {
  session,
  seal,
  unseal,
  checkOrigin,
  cookieOptions,
  origin,
} from "@/lib/session";
export const dynamic = "force-dynamic";
const name = "psico-pending-invitation";
export async function GET(request: NextRequest) {
  return NextResponse.json(
    {
      pending: !!request.cookies.get(name),
      authenticated: !!(await session()),
    },
    { headers: { "Cache-Control": "no-store" } },
  );
}
export async function POST(request: NextRequest) {
  if (!checkOrigin(request))
    return NextResponse.json({ error: "Origem inválida" }, { status: 403 });
  try {
    const body = await request.text();
    if (body.length > 512) throw new Error("INVALID_TOKEN");
    let token = (JSON.parse(body || "{}") as { token?: string }).token;
    if (!token) {
      const raw = request.cookies.get(name)?.value;
      if (raw) {
        const pending = unseal<{ token: string; expires: number }>(
          Buffer.from(raw, "base64url"),
        );
        if (pending.expires > Date.now()) token = pending.token;
      }
    }
    if (!token || !/^[-_a-zA-Z0-9]{40,100}$/.test(token))
      return NextResponse.json(
        { error: "Convite inválido ou expirado." },
        { status: 422 },
      );
    const current = await session();
    if (!current) {
      const response = NextResponse.json({
        login: origin() + "/api/auth/login?next=/convite",
      });
      response.cookies.set(
        name,
        seal({ token, expires: Date.now() + 600000 }).toString("base64url"),
        { ...cookieOptions(), maxAge: 600 },
      );
      return response;
    }
    const response = await fetch(
      (process.env.API_BASE_URL || "http://localhost:8080") +
        "/v1/invitations/accept",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + current.tokens.accessToken,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ token }),
        cache: "no-store",
        signal: AbortSignal.timeout(15000),
      },
    );
    if (!response.ok)
      return NextResponse.json(
        {
          error:
            "Não foi possível aceitar. Confira o email verificado e a validade do convite.",
        },
        { status: response.status },
      );
    const result = NextResponse.json({ accepted: true });
    result.cookies.set(name, "", { ...cookieOptions(), maxAge: 0 });
    return result;
  } catch {
    return NextResponse.json(
      { error: "Convite indisponível. Tente novamente." },
      { status: 503 },
    );
  }
}
