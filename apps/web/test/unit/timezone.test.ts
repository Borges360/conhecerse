import { describe, it, expect } from "vitest";
import { localToInstant, moneyToCents } from "../../src/lib/timezone";
describe("T-037 local time and DST", () => {
  it("keeps the local clock across daylight saving", () => {
    expect(localToInstant("2026-03-01T09:00", "America/New_York")).toBe(
      "2026-03-01T14:00:00.000Z",
    );
    expect(localToInstant("2026-03-15T09:00", "America/New_York")).toBe(
      "2026-03-15T13:00:00.000Z",
    );
  });
  it("rejects nonexistent times", () =>
    expect(() =>
      localToInstant("2026-03-08T02:30", "America/New_York"),
    ).toThrow("não existe"));
  it("requires an offset for ambiguous times", () => {
    expect(() =>
      localToInstant("2026-11-01T01:30", "America/New_York"),
    ).toThrow("ambíguo");
    expect(localToInstant("2026-11-01T01:30", "America/New_York", -300)).toBe(
      "2026-11-01T06:30:00.000Z",
    );
  });
  it("uses the clinic timezone instead of the browser timezone", () =>
    expect(localToInstant("2026-10-13T09:00", "America/Sao_Paulo")).toBe(
      "2026-10-13T12:00:00.000Z",
    ));
});
describe("BIL integer money boundary", () => {
  it.each([
    ["0", 0],
    ["180,00", 18000],
    ["180.5", 18050],
    ["20000", 2000000],
  ])("%s -> %d", (input, cents) => expect(moneyToCents(input)).toBe(cents));
  it.each(["-1", "1.001", "NaN", "Infinity", "20000,01", "1e4", "1,000.00"])(
    "rejects %s",
    (input) => expect(() => moneyToCents(input)).toThrow(),
  );
});
