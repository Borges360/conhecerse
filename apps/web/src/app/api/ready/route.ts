import { pool, configuration } from "@/lib/session";
export const dynamic = "force-dynamic";
export async function GET() {
  try {
    await pool.query("select 1");
    await configuration();
    const api = await fetch(
      (process.env.API_BASE_URL || "http://127.0.0.1:8080") +
        "/actuator/health/readiness",
      { cache: "no-store", signal: AbortSignal.timeout(4000) },
    );
    if (!api.ok) throw new Error();
    return Response.json(
      { status: "UP" },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch {
    return Response.json(
      { status: "DOWN" },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }
}
