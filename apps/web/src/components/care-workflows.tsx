"use client";
import { useCallback, useEffect, useState } from "react";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
import { Field, SaveButton, ErrorNotice, Notice, date, Status } from "./ui";

type Page = { items: Row[] };
export function CareWorkflowsPanel({
  orgId,
  patientId,
  clinician = false,
}: {
  orgId: string;
  patientId: string;
  clinician?: boolean;
}) {
  const base = "/v1/orgs/" + orgId;
  const [protocols, setProtocols] = useState<Row[]>([]),
    [requests, setRequests] = useState<Row[]>([]),
    [exports, setExports] = useState<Row[]>([]),
    [guardians, setGuardians] = useState<Row[]>([]),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false);
  const [availableNotes, setAvailableNotes] = useState<Row[]>([]);
  const load = useCallback(async () => {
    const paths = [
      "/patients/" + patientId + "/remote-protocols",
      clinician
        ? "/privacy-requests"
        : "/patients/" + patientId + "/privacy-requests",
      "/record-exports",
    ];
    const values = await Promise.allSettled(
      paths.map((p) => request<Page>(base + p)),
    );
    if (values[0].status === "fulfilled") setProtocols(values[0].value.items);
    if (values[1].status === "fulfilled")
      setRequests(
        values[1].value.items.filter(
          (r) => !r.patientId || r.patientId === patientId,
        ),
      );
    if (values[2].status === "fulfilled")
      setExports(
        values[2].value.items.filter((r) => r.patientId === patientId),
      );
    if (clinician)
      try {
        setAvailableNotes(
          (
            await request<Page>(
              base + "/patients/" + patientId + "/clinical-notes?limit=100",
            )
          ).items.filter((n) => n.status === "SIGNED"),
        );
        setGuardians(
          (await request<Page>(base + "/patients/" + patientId + "/guardians"))
            .items,
        );
      } catch {
        /* administrator permissions are enforced by API */
      }
  }, [base, patientId, clinician]);
  useEffect(() => {
    void load().catch((e) => setError(e.message));
  }, [load]);
  async function act(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const value = await request<Row>(base + path, {
        method: "POST",
        body: body ? JSON.stringify(body) : undefined,
      });
      await load();
      setNotice("Atualização registrada.");
      return value;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível concluir.");
      return null;
    } finally {
      setBusy(false);
    }
  }
  async function download(id: string, preview = false) {
    const r = await act(
      "/record-exports/" + id + (preview ? "/preview" : "/download"),
    );
    if (r?.downloadUrl) window.location.assign(String(r.downloadUrl));
  }
  return (
    <section className="panel settings-panel">
      <h2>Protocolo remoto e direitos do paciente</h2>
      {error && <ErrorNotice message={error} />}{" "}
      {notice && <Notice>{notice}</Notice>}
      <details>
        <summary>Registrar combinado para atendimento remoto</summary>
        <form
          className="entity-form"
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            const b: Record<string, unknown> = {
              interruptionPlan: String(f.get("interruptionPlan")),
              acuteRiskPlan: String(f.get("acuteRiskPlan")),
              privacyGuidance: String(f.get("privacyGuidance")),
              privacyAcknowledged: f.get("privacyAcknowledged") === "on",
            };
            if (f.get("currentLocation")) {
              b.currentLocation = String(f.get("currentLocation"));
              b.clinicalIndication = String(f.get("clinicalIndication"));
            }
            void act("/patients/" + patientId + "/remote-protocols", b);
          }}
        >
          <Field label="Se a conexão for interrompida">
            <textarea
              name="interruptionPlan"
              minLength={8}
              maxLength={4000}
              required
            />
          </Field>
          <Field label="Protocolo combinado para risco agudo">
            <textarea
              name="acuteRiskPlan"
              minLength={8}
              maxLength={4000}
              required
            />
          </Field>
          <Field label="Orientações de privacidade">
            <textarea
              name="privacyGuidance"
              minLength={8}
              maxLength={4000}
              required
            />
          </Field>
          {clinician && (
            <div className="form-grid">
              <Field label="Localização, quando clinicamente indicada">
                <input name="currentLocation" maxLength={500} />
              </Field>
              <Field label="Finalidade clínica da localização">
                <input
                  name="clinicalIndication"
                  minLength={8}
                  maxLength={500}
                />
              </Field>
            </div>
          )}
          <label className="checkbox-label">
            <input type="checkbox" name="privacyAcknowledged" required />
            Orientações compartilhadas e compreendidas
          </label>
          <SaveButton busy={busy}>Enviar para revisão profissional</SaveButton>
        </form>
      </details>
      {protocols.map((p) => (
        <article className="document-card" key={p.id}>
          <h3>Protocolo · versão {p.version}</h3>
          <Status value={p.status || "PENDING"} />
          {Object.entries((p.protocol || {}) as Record<string, unknown>)
            .filter(([k]) =>
              [
                "interruptionPlan",
                "acuteRiskPlan",
                "privacyGuidance",
                "currentLocation",
              ].includes(k),
            )
            .map(([k, v]) => (
              <p key={k}>
                {
                  (
                    {
                      interruptionPlan: "Interrupção",
                      acuteRiskPlan: "Risco agudo",
                      privacyGuidance: "Privacidade",
                      currentLocation: "Localização",
                    } as Record<string, string>
                  )[k]
                }
                : {String(v)}
              </p>
            ))}
          {clinician && p.status === "PENDING" && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                void act(
                  "/patients/" +
                    patientId +
                    "/remote-protocols/" +
                    p.id +
                    "/review",
                  {
                    decision: String(f.get("decision")),
                    evidence: String(f.get("evidence")),
                  },
                );
              }}
            >
              <Field label="Referência da revisão clínica">
                <input name="evidence" minLength={8} required />
              </Field>
              <Field label="Decisão">
                <select name="decision">
                  <option value="APPROVE">Aprovar</option>
                  <option value="REJECT">Rejeitar</option>
                </select>
              </Field>
              <SaveButton busy={busy}>Registrar revisão</SaveButton>
            </form>
          )}
        </article>
      ))}
      <h3>Acesso e portabilidade</h3>
      {!clinician && (
        <div className="button-row">
          {["ACCESS", "PORTABILITY"].map((type) => (
            <button
              className="button button-outline"
              disabled={busy}
              key={type}
              onClick={() =>
                void act("/patients/" + patientId + "/privacy-requests", {
                  type,
                })
              }
            >
              {type === "ACCESS"
                ? "Solicitar acesso"
                : "Solicitar portabilidade"}
            </button>
          ))}
        </div>
      )}
      {requests.map((r) => (
        <article className="document-card" key={r.id}>
          <h4>
            {r.requestType === "PORTABILITY"
              ? "Portabilidade"
              : "Pedido de dados"}
          </h4>
          <Status value={r.status || "OPEN"} />
          {Boolean(r.dueAt) && (
            <p>Prazo operacional: {date(String(r.dueAt))}</p>
          )}
          {clinician &&
            ["OPEN", "IN_REVIEW"].includes(r.status || "") &&
            ["ACCESS", "PORTABILITY"].includes(String(r.requestType)) && (
              <form
                className="entity-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget);
                  void act("/privacy-requests/" + r.id + "/prepare-export", {
                    noteIds: f.getAll("noteIds").map(String),
                    reviewedSummary: String(f.get("summary")),
                  });
                }}
              >
                <fieldset>
                  <legend>Selecione as notas assinadas após revisão</legend>
                  {availableNotes.map((n) => (
                    <label key={n.id} className="checkbox-label">
                      <input type="checkbox" name="noteIds" value={n.id} />
                      {date(String(n.occurredAt))} ·{" "}
                      {String(n.noteType || "Evolução")}
                    </label>
                  ))}
                </fieldset>
                <Field label="Conteúdo revisado para o paciente">
                  <textarea
                    name="summary"
                    minLength={8}
                    maxLength={50000}
                    required
                  />
                </Field>
                <SaveButton busy={busy}>
                  Preparar pacote para conferência
                </SaveButton>
              </form>
            )}
        </article>
      ))}
      {exports.map((r) => (
        <article className="document-card" key={r.id}>
          <h4>Pacote de registros</h4>
          <Status value={r.status || "PREPARED"} />
          {Boolean(r.expiresAt) && (
            <p>
              Disponível até{" "}
              {new Date(String(r.expiresAt)).toLocaleString("pt-BR")}
            </p>
          )}
          {clinician ? (
            <>
              <button
                className="text-button"
                disabled={busy}
                onClick={() => void download(r.id, true)}
              >
                Conferir conteúdo
              </button>
              {r.status === "PREPARED" && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    void act("/record-exports/" + r.id + "/release", {
                      evidence: String(f.get("evidence")),
                      expiresAt: new Date(
                        String(f.get("expiry")),
                      ).toISOString(),
                    });
                  }}
                >
                  <Field label="Evidência da revisão">
                    <input name="evidence" minLength={8} required />
                  </Field>
                  <Field label="Expiração (até sete dias)">
                    <input name="expiry" type="datetime-local" required />
                  </Field>
                  <SaveButton busy={busy}>
                    Liberar ao destinatário autenticado
                  </SaveButton>
                </form>
              )}
              {r.status !== "REVOKED" && (
                <button
                  className="text-button danger-text"
                  disabled={busy}
                  onClick={() =>
                    void act("/record-exports/" + r.id + "/revoke")
                  }
                >
                  Revogar pacote
                </button>
              )}
            </>
          ) : (
            <button
              className="button"
              disabled={
                busy || !["RELEASED", "DELIVERED"].includes(r.status || "")
              }
              onClick={() => void download(r.id)}
            >
              Baixar meus registros
            </button>
          )}
        </article>
      ))}
      {clinician && (
        <details>
          <summary>Responsáveis legais e revisão clínica individual</summary>
          <form
            className="entity-form"
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              void act("/patients/" + patientId + "/guardians", {
                guardianUserId: String(f.get("user")),
                relationship: String(f.get("relationship")),
                accessScope: ["SCHEDULE", "BILLING"],
                evidence: String(f.get("evidence")),
              });
            }}
          >
            <Field label="ID da conta do responsável">
              <input name="user" required />
            </Field>
            <Field label="Vínculo legal">
              <input name="relationship" required />
            </Field>
            <Field label="Evidência da revisão legal">
              <input name="evidence" minLength={8} required />
            </Field>
            <SaveButton busy={busy}>Registrar responsável</SaveButton>
          </form>
          {guardians.map((g) => (
            <article className="document-card" key={g.id}>
              <h4>
                {String(g.relationship)} · {String(g.guardianUserId)}
              </h4>
              <p>
                {g.clinicalReviewer
                  ? "Liberação clínica revisada"
                  : "Liberação clínica pendente"}
              </p>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget);
                  void act(
                    "/patients/" +
                      patientId +
                      "/guardians/" +
                      g.id +
                      "/" +
                      String(f.get("stage")),
                    {
                      evidence: String(f.get("evidence")),
                      expiresAt: new Date(
                        String(f.get("expiry")),
                      ).toISOString(),
                    },
                  );
                }}
              >
                <Field label="Etapa">
                  <select name="stage">
                    <option value="legal-review">Revisão jurídica</option>
                    <option value="clinical-review">
                      Revisão do profissional vinculado
                    </option>
                  </select>
                </Field>
                <Field label="Evidência">
                  <input name="evidence" minLength={8} required />
                </Field>
                <Field label="Expiração (até 90 dias)">
                  <input type="datetime-local" name="expiry" required />
                </Field>
                <SaveButton busy={busy}>
                  Registrar aprovação individual
                </SaveButton>
              </form>
              <button
                className="text-button danger-text"
                disabled={busy}
                onClick={() =>
                  void act(
                    "/patients/" +
                      patientId +
                      "/guardians/" +
                      g.id +
                      "/revoke-clinical",
                  )
                }
              >
                Revogar liberação clínica
              </button>
            </article>
          ))}
        </details>
      )}
      <Notice>
        O profissional revisa o conteúdo antes da liberação. Materiais de testes
        permanecem restritos. Cada download exige autenticação e um link que
        expira em 60 segundos.
      </Notice>
    </section>
  );
}
