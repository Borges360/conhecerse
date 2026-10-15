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
  const base = new Date();
  base.setHours(0, 0, 0, 0);
  const monday = new Date(base);
  monday.setDate(base.getDate() - ((base.getDay() + 6) % 7));
  return Array.from({ length: 14 }, (_, i) => {
    const start = new Date(monday);
    start.setDate(monday.getDate() + (i % 5));
    start.setHours(9 + Math.floor(i / 5) * 3 + (i % 2), i % 2 === 0 ? 0 : 30);
    return {
      id: patient(100 + i),
      patientId: demoPatients[i % 5].id,
      professionalId: patient(90),
      startAt: start.toISOString(),
      endAt: new Date(start.getTime() + 50 * 60000).toISOString(),
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
  dueOn: new Date().toISOString().slice(0, 10),
  status: i < 2 ? "PAID" : "OPEN",
  version: 1,
}));
