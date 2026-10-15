import { NextResponse } from "next/server";
import { session } from "@/lib/session";
export const dynamic = "force-dynamic";
export async function GET() {
  try {
    const current = await session();
    return NextResponse.json(
      current
        ? {
            authenticated: true,
            csrfToken: current.csrfToken,
            name: current.tokens.name,
          }
        : { authenticated: false },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch {
    return NextResponse.json(
      { authenticated: false },
      { headers: { "Cache-Control": "no-store" } },
    );
  }
}
