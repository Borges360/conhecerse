import { localToInstant } from "./timezone";

const demoTimeZone = "America/Sao_Paulo";
function localDateKey(value = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: demoTimeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(value);
  const part = (type: string) => parts.find((item) => item.type === type)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}
function shiftDate(value: string, days: number) {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day + days))
    .toISOString()
    .slice(0, 10);
}

// Synthetic fixtures are restricted to /demonstracao. They never enter the API or production storage.
const patient = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
export const demoPatients = [
  {
    id: patient(1),
    displayName: "Marina S. (fictícia)",
    status: "ACTIVE",
    assignedProfessionals: [patient(90)],
    version: 1,
  },
  {
    id: patient(2),
    displayName: "Rafael M. (fictício)",
    status: "ACTIVE",
    assignedProfessionals: [patient(90)],
    version: 1,
  },
  {
    id: patient(3),
    displayName: "Clara A. (fictícia)",
    status: "ACTIVE",
    assignedProfessionals: [patient(90)],
    version: 1,
  },
  {
    id: patient(4),
    displayName: "João P. (fictício)",
    status: "ACTIVE",
    assignedProfessionals: [patient(90)],
    version: 1,
  },
  {
    id: patient(5),
    displayName: "Luiza R. (fictícia)",
    status: "ACTIVE",
    assignedProfessionals: [patient(90)],
    version: 1,
  },
];
export const demoProfessional = {
  id: patient(90),
  displayName: "Paola Gularte · demonstração",
  crpUf: "RS",
  crpNumber: "Em revisão",
  verificationStatus: "PENDING",
  version: 1,
};
export function demoAppointments() {
  const today = localDateKey();
  const [year, month, day] = today.split("-").map(Number);
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  const monday = shiftDate(today, -((weekday + 6) % 7));
  return Array.from({ length: 14 }, (_, i) => {
    const date = shiftDate(monday, i % 5);
    const hour = 9 + Math.floor(i / 5) * 3 + (i % 2);
    const minute = i % 2 === 0 ? 0 : 30;
    const startAt = localToInstant(
      `${date}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`,
      demoTimeZone,
    );
    return {
      id: patient(100 + i),
      patientId: demoPatients[i % 5].id,
      professionalId: patient(90),
      startAt,
      endAt: new Date(Date.parse(startAt) + 50 * 60000).toISOString(),
      modality: i % 4 === 0 ? "IN_PERSON" : "REMOTE",
      status: i % 4 === 0 ? "SCHEDULED" : "CONFIRMED",
      priceCents: 18000,
      version: 1,
    };
  });
}
export const demoInvoices = demoPatients.slice(0, 4).map((p, i) => ({
  id: patient(200 + i),
  payerId: p.id,
  amountCents: 18000,
  paidCents: i < 2 ? 18000 : 0,
  dueOn: localDateKey(),
  status: i < 2 ? "PAID" : "OPEN",
  version: 1,
}));
