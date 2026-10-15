import { describe, it, expect, vi } from "vitest";
import { webcrypto } from "node:crypto";
import { randomId, checksum } from "../../src/lib/browser-crypto";
describe("HTTP do laboratório", () => {
  it("gera UUID v4 com CSPRNG mesmo sem randomUUID e subtle", () => {
    vi.stubGlobal("crypto", {
      getRandomValues: webcrypto.getRandomValues.bind(webcrypto),
    });
    try {
      const values = new Set(Array.from({ length: 100 }, () => randomId()));
      expect(values.size).toBe(100);
      for (const id of values)
        expect(id).toMatch(
          /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
        );
    } finally {
      vi.unstubAllGlobals();
    }
  });
  it("calcula SHA-256 conhecido sem depender de secure context", () => {
    expect(checksum(new TextEncoder().encode("abc").buffer)).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });
});
