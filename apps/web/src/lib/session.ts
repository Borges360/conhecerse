import "server-only";
import { cookies } from "next/headers";
import { Pool } from "pg";
import {
  randomBytes,
  createHash,
  createCipheriv,
  createDecipheriv,
  timingSafeEqual,
} from "node:crypto";
import * as oidc from "openid-client";

const globalPool = globalThis as unknown as { conhecersePool?: Pool };
export const pool =
  globalPool.conhecersePool ??
  new Pool({
    connectionString: process.env.BFF_DATABASE_URL,
    max: 6,
    connectionTimeoutMillis: 5000,
  });
if (process.env.NODE_ENV !== "production") globalPool.conhecersePool = pool;
export const origin = () => process.env.APP_ORIGIN || "http://localhost:3000";
export const secure = () => process.env.COOKIE_SECURE === "true";
export const cookieName = () =>
  secure() ? "__Host-psico-session" : "psico-session-local";
export const hash = (value: string) =>
  createHash("sha256").update(value).digest();
function key() {
  const raw = Buffer.from(process.env.BFF_ENCRYPTION_KEY || "", "base64");
  if (raw.length !== 32) throw new Error("BFF_ENCRYPTION_KEY_REQUIRED");
  return raw;
}
export function seal(value: unknown) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  cipher.setAAD(Buffer.from("conhecerse:bff:v1"));
  const data = Buffer.concat([
    cipher.update(JSON.stringify(value), "utf8"),
    cipher.final(),
  ]);
  return Buffer.concat([iv, cipher.getAuthTag(), data]);
}
export function unseal<T>(raw: Buffer): T {
  const decipher = createDecipheriv("aes-256-gcm", key(), raw.subarray(0, 12));
  decipher.setAAD(Buffer.from("conhecerse:bff:v1"));
  decipher.setAuthTag(raw.subarray(12, 28));
  return JSON.parse(
    Buffer.concat([
      decipher.update(raw.subarray(28)),
      decipher.final(),
    ]).toString("utf8"),
  );
}
export type Tokens = {
  accessToken: string;
  refreshToken?: string;
  expiresAt: number;
  name?: string;
  subject: string;
};
export type Session = { id: Buffer; csrfToken: string; tokens: Tokens };
let configPromise: Promise<oidc.Configuration> | undefined;
export function configuration() {
  if (
    !process.env.OIDC_ISSUER ||
    !process.env.OIDC_CLIENT_ID ||
    !process.env.OIDC_CLIENT_SECRET
  )
    throw new Error("OIDC_NOT_CONFIGURED");
  const issuer = new URL(process.env.OIDC_ISSUER);
  if (
    process.env.APP_ENV === "production" &&
    (!secure() || !origin().startsWith("https://"))
  )
    throw new Error("SECURE_SESSION_REQUIRED");
  if (issuer.protocol !== "https:" && process.env.APP_ENV === "production")
    throw new Error("OIDC_TLS_REQUIRED");
  configPromise ??= oidc.discovery(
    issuer,
    process.env.OIDC_CLIENT_ID,
    process.env.OIDC_CLIENT_SECRET,
    undefined,
    issuer.protocol === "http:"
      ? { execute: [oidc.allowInsecureRequests] }
      : undefined,
  );
  return configPromise;
}
export async function session(): Promise<Session | null> {
  const jar = await cookies();
  const raw = jar.get(cookieName())?.value;
  if (!raw) return null;
  const id = hash(raw);
  const result = await pool.query(
    "select encrypted_tokens,csrf_token from bff.sessions where id_hash=$1 and expires_at>now()",
    [id],
  );
  if (!result.rows.length) return null;
  let tokens = unseal<Tokens>(result.rows[0].encrypted_tokens);
  if (tokens.expiresAt < Date.now() + 30000) {
    const client = await pool.connect();
    try {
      await client.query("begin");
      const locked = await client.query(
        "select encrypted_tokens from bff.sessions where id_hash=$1 and expires_at>now() for update",
        [id],
      );
      if (!locked.rows.length) {
        await client.query("rollback");
        return null;
      }
      tokens = unseal<Tokens>(locked.rows[0].encrypted_tokens);
      if (tokens.expiresAt < Date.now() + 30000) {
        if (!tokens.refreshToken) {
          await client.query("delete from bff.sessions where id_hash=$1", [id]);
          await client.query("commit");
          return null;
        }
        const refreshed = await oidc.refreshTokenGrant(
          await configuration(),
          tokens.refreshToken,
        );
        tokens = {
          ...tokens,
          accessToken: refreshed.access_token,
          refreshToken: refreshed.refresh_token || tokens.refreshToken,
          expiresAt: Date.now() + (refreshed.expires_in || 120) * 1000,
        };
        await client.query(
          "update bff.sessions set encrypted_tokens=$1 where id_hash=$2",
          [seal(tokens), id],
        );
      }
      await client.query("commit");
    } catch {
      await client.query("rollback");
      await pool.query("delete from bff.sessions where id_hash=$1", [id]);
      return null;
    } finally {
      client.release();
    }
  }
  return { id, csrfToken: result.rows[0].csrf_token, tokens };
}
export function checkOrigin(request: Request) {
  return request.headers.get("origin") === origin();
}
export function checkCsrf(request: Request, value: string) {
  const supplied = Buffer.from(request.headers.get("x-csrf-token") || "");
  const expected = Buffer.from(value);
  return (
    checkOrigin(request) &&
    supplied.length === expected.length &&
    timingSafeEqual(supplied, expected)
  );
}
export const cookieOptions = () => ({
  httpOnly: true,
  secure: secure(),
  sameSite: "lax" as const,
  path: "/",
  maxAge: 28800,
});
