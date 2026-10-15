import { NextRequest, NextResponse } from "next/server";
import { session, checkCsrf, checkOrigin } from "@/lib/session";
export const dynamic = "force-dynamic";
async function proxy(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> },
) {
  const { path } = await params;
  if (
    path[0] !== "v1" ||
    path.some((s) => s === ".." || s.includes("/") || s.includes("\\"))
  )
    return NextResponse.json({ code: "INVALID_PATH" }, { status: 400 });
  const isPublic = path[1] === "public";
  const current = isPublic ? null : await session();
  if (!isPublic && !current)
    return NextResponse.json(
      { title: "Sessão expirada", code: "UNAUTHENTICATED", status: 401 },
      { status: 401 },
    );
  if (
    !["GET", "HEAD"].includes(request.method) &&
    !(current ? checkCsrf(request, current.csrfToken) : checkOrigin(request))
  )
    return NextResponse.json(
      { title: "Solicitação inválida", code: "CSRF_INVALID", status: 403 },
      { status: 403 },
    );
  const headers = new Headers();
  if (current)
    headers.set("Authorization", "Bearer " + current.tokens.accessToken);
  for (const name of ["content-type", "idempotency-key", "if-match"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const body = ["GET", "HEAD"].includes(request.method)
    ? undefined
    : await request.arrayBuffer();
  if (body && body.byteLength > 262144)
    return NextResponse.json({ code: "REQUEST_TOO_LARGE" }, { status: 413 });
  try {
    const result = await fetch(
      (process.env.API_BASE_URL || "http://127.0.0.1:8080") +
        "/" +
        path.map(encodeURIComponent).join("/") +
        request.nextUrl.search,
      {
        method: request.method,
        headers,
        body,
        cache: "no-store",
        redirect: "manual",
        signal: AbortSignal.timeout(30000),
      },
    );
    const responseHeaders = new Headers({
      "Cache-Control": "private, no-store",
      "Content-Type": result.headers.get("Content-Type") || "application/json",
    });
    for (const name of [
      "etag",
      "retry-after",
      "x-request-id",
      "content-disposition",
      "x-content-type-options",
    ]) {
      const value = result.headers.get(name);
      if (value) responseHeaders.set(name, value);
    }
    return new Response(result.body, {
      status: result.status,
      headers: responseHeaders,
    });
  } catch {
    return NextResponse.json(
      {
        type: "about:blank",
        title: "Serviço temporariamente indisponível",
        status: 503,
        code: "API_UNAVAILABLE",
        requestId: crypto.randomUUID(),
      },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }
}
export { proxy as GET, proxy as POST, proxy as PATCH };
