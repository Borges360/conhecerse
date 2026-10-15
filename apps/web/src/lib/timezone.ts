export function localToInstant(
  local: string,
  timezone: string,
  selectedOffsetMinutes?: number,
): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local);
  if (!match) throw new Error("Informe data e horário válidos.");
  const [, y, m, d, h, min] = match.map(Number);
  const naive = Date.UTC(y, m - 1, d, h, min);
  const format = new Intl.DateTimeFormat("sv-SE", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  const matches: { date: Date; offset: number }[] = [];
  for (let offset = -720; offset <= 840; offset += 15) {
    const candidate = new Date(naive - offset * 60000);
    if (format.format(candidate).replace(" ", "T") === local)
      matches.push({ date: candidate, offset });
  }
  if (!matches.length)
    throw new Error("Este horário não existe no fuso selecionado.");
  if (matches.length > 1 && selectedOffsetMinutes === undefined)
    throw new Error(
      "Horário ambíguo por mudança de fuso. Informe um horário fora da transição.",
    );
  const selected =
    selectedOffsetMinutes === undefined
      ? matches[0]
      : matches.find((m) => m.offset === selectedOffsetMinutes);
  if (!selected) throw new Error("O offset não é válido neste horário.");
  return selected.date.toISOString();
}
export function moneyToCents(value: string): number {
  if (!/^\d{1,5}([,.]\d{1,2})?$/.test(value))
    throw new Error("Informe o valor com até duas casas decimais.");
  const [whole, fraction = ""] = value.replace(",", ".").split(".");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  if (cents > 2000000) throw new Error("O valor máximo é R$ 20.000,00.");
  return cents;
}
