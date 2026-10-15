"use client";
import { useEffect, useState } from "react";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
import { Field, SaveButton, ErrorNotice, Notice, Loading } from "./ui";
import { AddressFields, addressFromForm } from "./completion-settings";
export function PatientDetails({
  orgId,
  patientId,
  onDone,
}: {
  orgId: string;
  patientId: string;
  onDone: () => void;
}) {
  const [record, setRecord] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [saved, setSaved] = useState(false);
  useEffect(() => {
    request<Row>("/v1/orgs/" + orgId + "/patients/" + patientId)
      .then(setRecord)
      .catch((e) => setError(e.message));
  }, [orgId, patientId]);
  if (!record) return error ? <ErrorNotice message={error} /> : <Loading />;
  return (
    <form
      className="entity-form"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError("");
        const f = new FormData(e.currentTarget);
        try {
          const updated = await request<Row>(
            "/v1/orgs/" + orgId + "/patients/" + patientId,
            {
              method: "PATCH",
              headers: { "If-Match": '"v' + record.version + '"' },
              body: JSON.stringify({
                fullName: String(f.get("fullName")),
                socialName: String(f.get("socialName")),
                email: String(f.get("email")) || null,
                phone: String(f.get("phone")),
                communicationPreference: String(
                  f.get("communicationPreference"),
                ),
                birthDate: String(f.get("birthDate")) || null,
                address: addressFromForm(f),
                ...(f.get("emergencyName")
                  ? {
                      emergencyContact: {
                        name: String(f.get("emergencyName")),
                        phone: String(f.get("emergencyPhone")),
                        relationship: String(f.get("emergencyRelationship")),
                        clinicalIndication: String(f.get("clinicalIndication")),
                      },
                    }
                  : {}),
              }),
            },
          );
          setRecord(updated);
          setSaved(true);
          onDone();
        } catch (e) {
          setError(e instanceof Error ? e.message : "Não foi possível salvar.");
        } finally {
          setBusy(false);
        }
      }}
    >
      <div className="form-grid">
        <Field label="Nome legal">
          <input
            name="fullName"
            defaultValue={String(record.fullName || "")}
            minLength={2}
            required
          />
        </Field>
        <Field label="Nome social">
          <input
            name="socialName"
            defaultValue={String(record.socialName || "")}
          />
        </Field>
        <Field label="Email">
          <input
            name="email"
            type="email"
            defaultValue={String(record.email || "")}
            required
          />
        </Field>
        <Field label="Telefone">
          <input name="phone" defaultValue={String(record.phone || "")} />
        </Field>
        <Field label="Canal combinado">
          <select
            name="communicationPreference"
            defaultValue={String(record.communicationPreference || "EMAIL")}
          >
            <option value="EMAIL">Email</option>
            <option value="WHATSAPP">WhatsApp com consentimento</option>
            <option value="NONE">Sem notificações</option>
          </select>
        </Field>
      </div>
      <Field label="Nascimento">
        <input
          name="birthDate"
          type="date"
          defaultValue={String(record.birthDate || "")}
        />
      </Field>
      <fieldset>
        <legend>Endereço opcional</legend>
        <AddressFields address={record.address as Record<string, unknown>} />
      </fieldset>
      <details>
        <summary>Contato de emergência, quando clinicamente indicado</summary>
        <Notice>
          O registro exige vínculo profissional e finalidade clínica. Preencha
          somente os dados necessários.
        </Notice>
        {[
          ["emergencyName", "Nome", "name"],
          ["emergencyPhone", "Telefone com código do país", "phone"],
          ["emergencyRelationship", "Relação com o paciente", "relationship"],
          ["clinicalIndication", "Finalidade clínica", "clinicalIndication"],
        ].map(([name, label, key]) => (
          <Field label={label} key={name}>
            <input
              name={name}
              defaultValue={String(
                (record.emergencyContact as Record<string, unknown>)?.[key] ||
                  "",
              )}
            />
          </Field>
        ))}
      </details>
      {error && <ErrorNotice message={error} />}{" "}
      {saved && <Notice>Cadastro atualizado.</Notice>}
      <SaveButton busy={busy}>Salvar cadastro</SaveButton>
    </form>
  );
}
