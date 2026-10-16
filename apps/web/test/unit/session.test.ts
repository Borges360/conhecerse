import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { discovery } = vi.hoisted(() => ({ discovery: vi.fn() }));

vi.mock("server-only", () => ({}));
vi.mock("openid-client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("openid-client")>()),
  discovery,
}));

describe("OIDC discovery configuration", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    vi.stubEnv("OIDC_ISSUER", "http://keycloak.test/realms/conhecerse");
    vi.stubEnv("OIDC_CLIENT_ID", "conhecerse-web");
    vi.stubEnv("OIDC_CLIENT_SECRET", "synthetic-test-secret");
    vi.stubEnv("APP_ENV", "dev");
  });

  afterEach(() => vi.unstubAllEnvs());

  it("retries discovery after a transient failure", async () => {
    const readyConfiguration = {};
    discovery
      .mockRejectedValueOnce(new Error("temporary discovery failure"))
      .mockResolvedValueOnce(readyConfiguration);
    const { configuration } = await import("../../src/lib/session");

    await expect(configuration()).rejects.toThrow("temporary discovery failure");
    await expect(configuration()).resolves.toBe(readyConfiguration);
    expect(discovery).toHaveBeenCalledTimes(2);
  });
});
