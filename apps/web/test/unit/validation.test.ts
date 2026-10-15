import { it, expect } from "vitest";
import { validateRequest } from "../../src/lib/validation";
it("valida e permite remover o endereço opcional sem aceitar campos extras", () => {
  expect(() =>
    validateRequest("PatientPatch", { address: null, birthDate: null }),
  ).not.toThrow();
  expect(() =>
    validateRequest("PatientPatch", {
      address: {
        street: "Rua sintética",
        city: "Cidade",
        state: "RS",
        postalCode: "00000000",
      },
    }),
  ).not.toThrow();
  expect(() =>
    validateRequest("PatientPatch", {
      address: {
        street: "Rua",
        city: "Cidade",
        state: "RS",
        postalCode: "00000000",
        clinicalNarrative: "FORBIDDEN",
      },
    }),
  ).toThrow();
});
it("contato de emergência exige indicação clínica e acesso emergencial não aceita escrita", () => {
  expect(() =>
    validateRequest("PatientPatch", {
      emergencyContact: { name: "Pessoa sintética", phone: "+5511999999999" },
    }),
  ).toThrow();
  expect(() =>
    validateRequest("BreakGlassCreate", {
      patientId: "00000000-0000-4000-8000-000000000000",
      scope: "SIGNED_NOTES_READ",
      durationMinutes: 16,
      reason: "Motivo sintético",
    }),
  ).toThrow();
});
const patientId = "00000000-0000-4000-8000-000000000000";
it("aceita uma evolução com timezone explícito e campos opcionais ausentes", () =>
  expect(() =>
    validateRequest("ClinicalNoteCreate", {
      patientId,
      occurredAt: "2026-10-13T10:00:00-03:00",
      narrative: "Registro sintético",
    }),
  ).not.toThrow());
it("rejeita centavos fracionários antes de solicitar um agendamento", () =>
  expect(() =>
    validateRequest("AppointmentCreate", {
      patientId,
      professionalId: patientId,
      startAt: "2026-10-13T13:00:00Z",
      endAt: "2026-10-13T13:50:00Z",
      modality: "REMOTE",
      priceCents: 18000.5,
    }),
  ).toThrow());
it("rejeita campos estranhos ao contrato financeiro", () =>
  expect(() =>
    validateRequest("PaymentCreate", {
      method: "PIX",
      cardNumber: "SYNTHETIC_FORBIDDEN",
    }),
  ).toThrow());
it("exige justificativa suficiente para lançamento manual", () =>
  expect(() =>
    validateRequest("ManualPayment", { amountCents: 18000, reason: "x" }),
  ).toThrow());
