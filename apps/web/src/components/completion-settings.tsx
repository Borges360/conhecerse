"use client";
import { useEffect, useState } from "react";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
import { Field, SaveButton, ErrorNotice, Notice, Status, brl } from "./ui";

const values = (value: unknown) =>
  Array.isArray(value) ? value.map(String).join(", ") : "";
const list = (value: FormDataEntryValue | null) =>
  String(value || "")
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
export function AddressFields({
  address = {},
}: {
  address?: Record<string, unknown>;
}) {
  return (
    <div className="form-grid">
      {[
        ["street", "Logradouro"],
        ["number", "Número"],
        ["complement", "Complemento"],
        ["city", "Cidade"],
        ["state", "UF"],
        ["postalCode", "CEP (somente números)"],
      ].map(([key, label]) => (
        <Field label={label} key={key}>
          <input
            name={key}
            defaultValue={String(address[key] || "")}
            maxLength={key === "state" ? 2 : key === "postalCode" ? 8 : 200}
          />
        </Field>
      ))}
    </div>
  );
}
export function addressFromForm(f: FormData) {
  const address = Object.fromEntries(
    ["street", "number", "complement", "city", "state", "postalCode"]
      .filter((k) => f.get(k))
      .map((k) => [k, String(f.get(k))]),
  );
  return Object.keys(address).length ? address : null;
}

export function CompletionSettings({
  orgId,
  organization,
  professionals,
  patients,
  onDone,
}: {
  orgId: string;
  organization: Row | null;
  professionals: Row[];
  patients: Row[];
  onDone: () => void;
}) {
  const base = "/v1/orgs/" + orgId;
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [subscriptions, setSubscriptions] = useState<Row[]>([]),
    [plans, setPlans] = useState<Row[]>([]),
    [access, setAccess] = useState<Row[]>([]),
    [notes, setNotes] = useState<Row[]>([]),
    [deliveries, setDeliveries] = useState<Row[]>([]),
    [metrics, setMetrics] = useState<Row[]>([]),
    [history, setHistory] = useState<Row[]>([]),
    [professional, setProfessional] = useState("");
  const pro =
    professionals.find((p) => p.id === professional) || professionals[0];
  const registration = (organization?.registration || {}) as Record<
    string,
    unknown
  >;
  async function load(path: string, update: (items: Row[]) => void) {
    try {
      update((await request<{ items: Row[] }>(base + path)).items);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Serviço indisponível.");
    }
  }
  async function mutate(
    path: string,
    body?: unknown,
    method = "POST",
    version?: number,
  ) {
    setBusy(true);
    setError("");
    try {
      const r = await request<Row>(base + path, {
        method,
        headers: version ? { "If-Match": '"v' + version + '"' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      setNotice("Atualização registrada.");
      onDone();
      return r;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível salvar.");
      return null;
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    setNotes([]);
    setNotice("");
  }, [orgId]);
  return (
    <section className="panel settings-panel">
      <h2>Cadastro, assinatura e controle de acesso</h2>
      {error && <ErrorNotice message={error} />}{" "}
      {notice && <Notice>{notice}</Notice>}
      <details>
        <summary>Identificação do responsável e endereço da clínica</summary>
        <form
          className="entity-form"
          key={organization?.version}
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void mutate(
              "",
              {
                registration: {
                  responsibleName: String(f.get("responsibleName")),
                  responsibleTaxId: String(f.get("taxId")),
                  ...(f.get("legalName")
                    ? { legalName: String(f.get("legalName")) }
                    : {}),
                  address: addressFromForm(f),
                },
              },
              "PATCH",
              organization?.version || 1,
            );
          }}
        >
          <Field label="Nome do responsável">
            <input
              name="responsibleName"
              defaultValue={String(registration.responsibleName || "")}
              required
            />
          </Field>
          <Field label="CPF/CNPJ do responsável">
            <input
              name="taxId"
              pattern="([0-9]{11}|[0-9]{14})"
              defaultValue={String(registration.responsibleTaxId || "")}
              required
            />
          </Field>
          <Field label="Razão social">
            <input
              name="legalName"
              defaultValue={String(registration.legalName || "")}
            />
          </Field>
          <AddressFields
            address={registration.address as Record<string, unknown>}
          />
          <SaveButton busy={busy}>Salvar identificação</SaveButton>
        </form>
      </details>
      <details>
        <summary>Idiomas, especialidades e atendimento público</summary>
        {pro && (
          <>
            <Field label="Profissional">
              <select
                value={pro.id}
                onChange={(e) => setProfessional(e.target.value)}
              >
                {professionals.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.displayName}
                  </option>
                ))}
              </select>
            </Field>
            <form
              className="entity-form"
              key={pro.id + ":" + pro.version}
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                void mutate(
                  "/professionals/" + pro.id,
                  {
                    languages: list(f.get("languages")),
                    specialties: list(f.get("specialties")),
                    appointmentTypes: [
                      ...(f.get("remote") === "on" ? ["REMOTE"] : []),
                      ...(f.get("inPerson") === "on" ? ["IN_PERSON"] : []),
                    ],
                    publicBio: String(f.get("bio")),
                    publicProfileEnabled: f.get("public") === "on",
                  },
                  "PATCH",
                  pro.version || 1,
                );
              }}
            >
              <Field label="Idiomas (separados por vírgula)">
                <input name="languages" defaultValue={values(pro.languages)} />
              </Field>
              <Field label="Especialidades autodeclaradas (separadas por vírgula)">
                <input
                  name="specialties"
                  defaultValue={values(pro.specialties)}
                />
              </Field>
              <Field label="Apresentação pública">
                <textarea
                  name="bio"
                  defaultValue={String(pro.publicBio || "")}
                  maxLength={2000}
                />
              </Field>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  name="remote"
                  defaultChecked={
                    Array.isArray(pro.appointmentTypes) &&
                    pro.appointmentTypes.includes("REMOTE")
                  }
                />
                Online
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  name="inPerson"
                  defaultChecked={
                    Array.isArray(pro.appointmentTypes) &&
                    pro.appointmentTypes.includes("IN_PERSON")
                  }
                />
                Presencial
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  name="public"
                  defaultChecked={pro.publicProfileEnabled === true}
                />
                Autorizar publicação do perfil
              </label>
              <SaveButton busy={busy}>Salvar perfil</SaveButton>
            </form>
            <p>
              Especialidades revisadas:{" "}
              {values(pro.reviewedSpecialties) || "Revisão pendente"}
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                void mutate("/professionals/" + pro.id + "/review", {
                  status: "VERIFIED",
                  evidence: String(f.get("evidence")),
                  reviewedSpecialties: list(f.get("specialties")),
                });
              }}
            >
              <Field label="Especialidades confirmadas na revisão">
                <input
                  name="specialties"
                  defaultValue={values(pro.reviewedSpecialties)}
                />
              </Field>
              <Field label="Referência verificável da revisão">
                <input name="evidence" minLength={8} required />
              </Field>
              <SaveButton busy={busy}>Registrar revisão</SaveButton>
            </form>
          </>
        )}
      </details>
      <details
        onToggle={(e) => {
          if (e.currentTarget.open) {
            void load("/platform-subscription", setSubscriptions);
            void load("/platform-subscription/plans", setPlans);
          }
        }}
      >
        <summary>Assinatura mensal da plataforma</summary>
        <Notice>
          A mensalidade usa a conta da plataforma. Preço e condições precisam de
          homologação de negócio antes da contratação.
        </Notice>
        {subscriptions.map((s) => (
          <article key={s.id} className="document-card">
            <h3>
              {String(s.planCode)} · {brl(Number(s.amountCents))}
            </h3>
            <Status value={s.status || "TRIAL"} />
            <p>
              Última conciliação:{" "}
              {s.lastReconciledAt
                ? new Date(String(s.lastReconciledAt)).toLocaleString("pt-BR")
                : "Pendente"}
            </p>
            {Array.isArray(s.charges) &&
              s.charges.map((c: Row) => (
                <p key={String(c.providerPaymentRef)}>
                  {String(c.dueOn)} · {brl(Number(c.amountCents))} ·{" "}
                  {String(c.status)}{" "}
                  {Boolean(c.checkoutUrl) && (
                    <a
                      href={String(c.checkoutUrl)}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Abrir cobrança
                    </a>
                  )}
                </p>
              ))}
            <div className="button-row">
              <button
                className="button button-outline"
                disabled={busy || s.status === "CANCELLED"}
                onClick={() =>
                  void mutate("/platform-subscription/reconcile").then(() =>
                    load("/platform-subscription", setSubscriptions),
                  )
                }
              >
                Conciliar
              </button>
              <button
                className="text-button danger-text"
                disabled={busy || s.status === "CANCELLED"}
                onClick={() =>
                  void mutate("/platform-subscription/cancel").then(() =>
                    load("/platform-subscription", setSubscriptions),
                  )
                }
              >
                Solicitar cancelamento
              </button>
            </div>
          </article>
        ))}
        {plans.length > 0 && (
          <form
            className="entity-form"
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              const plan = plans.find((p) => p.code === f.get("plan"));
              if (!plan) return;
              void mutate("/platform-subscription", {
                planCode: plan.code,
                termsVersion: plan.termsVersion,
                customer: {
                  name: String(f.get("name")),
                  email: String(f.get("email")),
                  cpfCnpj: String(f.get("taxId")),
                },
              }).then(() => load("/platform-subscription", setSubscriptions));
            }}
          >
            <Field label="Plano">
              <select name="plan">
                {plans
                  .filter((p) => p.approved)
                  .map((p) => (
                    <option key={String(p.code)} value={String(p.code)}>
                      {String(p.code)} · {brl(Number(p.amountCents))} · termos{" "}
                      {String(p.termsVersion)}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="Nome do contratante">
              <input name="name" required />
            </Field>
            <Field label="Email do contratante">
              <input name="email" type="email" required />
            </Field>
            <Field label="CPF/CNPJ do contratante">
              <input name="taxId" pattern="([0-9]{11}|[0-9]{14})" required />
            </Field>
            <label className="checkbox-label">
              <input type="checkbox" required />
              Conferi e aceito as condições do plano e sua política de
              cancelamento
            </label>
            <SaveButton busy={busy}>Contratar plano</SaveButton>
          </form>
        )}
      </details>
      <details
        onToggle={(e) => {
          if (e.currentTarget.open) void load("/break-glass", setAccess);
        }}
      >
        <summary>Acesso emergencial interno</summary>
        <form
          className="entity-form"
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void mutate("/break-glass", {
              patientId: String(f.get("patient")),
              scope: "SIGNED_NOTES_READ",
              durationMinutes: Number(f.get("minutes")),
              reason: String(f.get("reason")),
            }).then(() => load("/break-glass", setAccess));
          }}
        >
          <Field label="Paciente">
            <select name="patient" required>
              {patients.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Justificativa do acesso emergencial">
            <textarea name="reason" minLength={8} maxLength={2000} required />
          </Field>
          <Field label="Duração (minutos)">
            <input
              name="minutes"
              type="number"
              min={1}
              max={15}
              defaultValue={5}
              required
            />
          </Field>
          <SaveButton busy={busy}>Solicitar dupla aprovação</SaveButton>
        </form>
        {access.map((r) => (
          <article className="document-card" key={r.id}>
            <h3>Solicitação {r.id}</h3>
            <p>{String(r.reason)}</p>
            <Status value={r.status || "PENDING"} />
            {Boolean(r.expiresAt) && (
              <p>
                Expira em{" "}
                {new Date(String(r.expiresAt)).toLocaleString("pt-BR")}
              </p>
            )}
            {r.status === "PENDING" && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget);
                  void mutate("/break-glass/" + r.id + "/approve", {
                    reviewRole: String(f.get("role")),
                    evidence: String(f.get("evidence")),
                  }).then(() => load("/break-glass", setAccess));
                }}
              >
                <Field label="Responsabilidade da aprovação">
                  <select name="role">
                    <option value="SECURITY">Segurança / administração</option>
                    <option value="CLINICAL">
                      Profissional vinculado ao paciente
                    </option>
                  </select>
                </Field>
                <Field label="Evidência da revisão">
                  <input name="evidence" minLength={8} required />
                </Field>
                <SaveButton busy={busy}>
                  Registrar minha aprovação independente
                </SaveButton>
              </form>
            )}
            <div className="button-row">
              {r.status === "ACTIVE" && (
                <button
                  className="button button-outline"
                  disabled={busy}
                  onClick={() =>
                    void load("/break-glass/" + r.id + "/notes", setNotes)
                  }
                >
                  Consultar notas autorizadas
                </button>
              )}
              <button
                className="text-button danger-text"
                disabled={busy}
                onClick={() =>
                  void mutate("/break-glass/" + r.id + "/revoke").then(() => {
                    setNotes([]);
                    return load("/break-glass", setAccess);
                  })
                }
              >
                Revogar
              </button>
              {r.status === "PENDING" && (
                <button
                  className="text-button"
                  disabled={busy}
                  onClick={() =>
                    void mutate("/break-glass/" + r.id + "/deny").then(() =>
                      load("/break-glass", setAccess),
                    )
                  }
                >
                  Negar
                </button>
              )}
            </div>
          </article>
        ))}
        {notes.map((n) => (
          <article key={n.id}>
            <p>{String(n.occurredAt)}</p>
            <p>{String(n.narrative)}</p>
          </article>
        ))}
      </details>
      <details
        onToggle={(e) => {
          if (e.currentTarget.open)
            void load("/communications/deliveries", setDeliveries);
        }}
      >
        <summary>Entrega e falhas de mensagens</summary>
        {deliveries.map((n) => (
          <article className="integration-row" key={n.id}>
            <p>
              {String(n.channel)} · tentativa {String(n.attempts)} ·{" "}
              {String(n.failureCode || "")}
            </p>
            <Status value={n.status || "QUEUED"} />
            {n.status === "FAILED" &&
              n.failureCode !== "DELIVERY_UNCERTAIN" && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    void mutate("/communications/" + n.id + "/retry", {
                      decision: "APPROVE",
                      evidence: String(f.get("evidence")),
                    }).then(() =>
                      load("/communications/deliveries", setDeliveries),
                    );
                  }}
                >
                  <Field label="Justificativa do reprocessamento">
                    <input name="evidence" minLength={8} required />
                  </Field>
                  <SaveButton busy={busy}>
                    Reprocessar falha confirmada
                  </SaveButton>
                </form>
              )}
          </article>
        ))}
      </details>
      <details
        onToggle={(e) => {
          if (e.currentTarget.open) void load("/content-metrics", setMetrics);
        }}
      >
        <summary>Leituras agregadas dos conteúdos</summary>
        <p>
          Contagem de leituras por dia e conteúdo. Não identifica pessoas nem
          visitantes únicos.
        </p>
        {metrics.map((m) => (
          <p key={String(m.contentId) + String(m.day)}>
            {String(m.day)} · conteúdo {String(m.contentId)} ·{" "}
            {String(m.readCount)} leituras
          </p>
        ))}
      </details>
      <details
        onToggle={(e) => {
          if (e.currentTarget.open) void load("/settings/history", setHistory);
        }}
      >
        <summary>Histórico de políticas e canais</summary>
        {history.map((h) => (
          <article key={String(h.version)}>
            <h3>Versão {h.version}</h3>
            <p>
              {String(
                (h.policy as Record<string, unknown>)?.cancellationPolicy || "",
              )}
            </p>
            <p>
              Canal de contingência:{" "}
              {String(
                (h.policy as Record<string, unknown>)?.contingencyChannel || "",
              )}
            </p>
          </article>
        ))}
      </details>
    </section>
  );
}
