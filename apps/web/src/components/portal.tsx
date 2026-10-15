"use client";
import { useEffect, useState } from "react";
import {
  ArrowUpRight,
  Video,
  CalendarDays,
  FileText,
  Wallet,
  ShieldCheck,
  Download,
} from "lucide-react";
import { request } from "@/lib/api.generated";
import { Brand } from "./brand";
import {
  Empty,
  ErrorNotice,
  Loading,
  Notice,
  Status,
  brl,
  date,
  time,
  Field,
  SaveButton,
} from "./ui";
import type { Row } from "./workspace";
import Link from "next/link";
import { localToInstant } from "@/lib/timezone";
import { CareWorkflowsPanel } from "./care-workflows";
export function Portal() {
  const [orgs, setOrgs] = useState<Row[]>([]),
    [org, setOrg] = useState(""),
    [me, setMe] = useState<Row | null>(null),
    [appointments, setAppointments] = useState<Row[]>([]),
    [invoices, setInvoices] = useState<Row[]>([]),
    [documents, setDocuments] = useState<Row[]>([]),
    [materials, setMaterials] = useState<Row[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true),
    [message, setMessage] = useState(""),
    [prefVersion, setPrefVersion] = useState(1),
    [marketing, setMarketing] = useState(false);
  const [patientChoices, setPatientChoices] = useState<Row[]>([]),
    [patientChoice, setPatientChoice] = useState("");
  useEffect(() => {
    request<{ items: Row[] }>("/v1/me/organizations")
      .then((r) => {
        setOrgs(r.items);
        if (r.items[0]) setOrg(r.items[0].id);
        else setLoading(false);
      })
      .catch((e) => {
        setError(e.message);
        setLoading(false);
      });
  }, []);
  useEffect(() => {
    if (!org) return;
    setLoading(true);
    setError("");
    Promise.all(
      [
        "me",
        "appointments",
        "invoices",
        "documents",
        "materials",
        "preferences",
        "patients",
      ].map((path) =>
        request<Row & { items: Row[] }>(
          "/v1/orgs/" +
            org +
            "/portal/" +
            path +
            (path === "me" && patientChoice
              ? "?patientId=" + patientChoice
              : ""),
        ),
      ),
    )
      .then(([m, a, i, d, c, p, patients]) => {
        setPatientChoices(patients.items);
        setMe(m);
        setAppointments(a.items);
        setInvoices(i.items);
        setDocuments(d.items);
        setMaterials(c.items);
        setPrefVersion(p.version || 1);
        setMarketing(p.marketingEmail === true);
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [org, patientChoice]);
  async function action(path: string, body?: unknown) {
    try {
      const result = await request<Row>("/v1/orgs/" + org + path, {
        method: "POST",
        body: body ? JSON.stringify(body) : undefined,
      });
      setMessage("Solicitação registrada.");
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível concluir.");
      return null;
    }
  }
  return (
    <main className="portal-page" id="conteudo">
      <div className="portal-top">
        <Brand />
        {orgs.length > 0 && (
          <Field label="Seu vínculo de cuidado">
            <select
              value={org}
              onChange={(e) => {
                setPatientChoice("");
                setOrg(e.target.value);
              }}
            >
              {orgs.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.displayName}
                </option>
              ))}
            </select>
          </Field>
        )}
      </div>
      {patientChoices.length > 1 && (
        <Field label="Paciente vinculado">
          <select
            value={patientChoice || me?.id || ""}
            onChange={(e) => setPatientChoice(e.target.value)}
          >
            {patientChoices.map((p) => (
              <option key={p.id} value={p.id}>
                {p.displayName}
              </option>
            ))}
          </select>
        </Field>
      )}
      <span className="eyebrow">SEU ESPAÇO DE CUIDADO</span>
      <h1>
        Seu tempo.
        <br />
        <em>Seu caminho.</em>
      </h1>
      <p>
        {me?.displayName ? `Olá, ${me.displayName}. ` : ""}Aqui você acompanha
        seus encontros e os documentos e materiais disponibilizados pelo
        profissional.
      </p>
      {error && <ErrorNotice message={error} />}{" "}
      {message && <Notice>{message}</Notice>}
      {loading ? (
        <Loading />
      ) : !me ? (
        <Empty
          title="Seu espaço ainda está sendo preparado"
          description="Acesse pelo convite enviado pelo consultório para vincular sua conta ao cadastro do paciente."
        />
      ) : (
        <>
          <CareWorkflowsPanel orgId={org} patientId={me.id} />
          <section className="panel portal-section">
            <div className="panel-heading">
              <h2>
                <CalendarDays size={19} /> Meus encontros
              </h2>
            </div>
            {appointments.length ? (
              <div className="appointment-list">
                {appointments.map((a) => (
                  <div className="appointment-row" key={a.id}>
                    <span className="appointment-time">
                      {time(a.startAt!)}
                      <small>{date(a.startAt!)}</small>
                    </span>
                    <div className="appointment-person">
                      <strong>
                        {a.modality === "REMOTE"
                          ? "Atendimento online"
                          : "Atendimento presencial"}
                      </strong>
                      <small>America/Sao_Paulo</small>
                    </div>
                    <Status value={a.status!} />
                    <div className="button-row">
                      {a.status === "SCHEDULED" && (
                        <button
                          className="text-button"
                          onClick={() =>
                            action(
                              "/appointments/" + a.id + "/actions/confirm",
                            ).then(
                              (r) =>
                                r &&
                                setAppointments((rows) =>
                                  rows.map((v) => (v.id === r.id ? r : v)),
                                ),
                            )
                          }
                        >
                          Confirmar
                        </button>
                      )}
                      {a.modality === "REMOTE" && (
                        <Link
                          className="text-button"
                          href={"/sessao/" + a.id + "?org=" + org}
                        >
                          Entrar <Video size={16} />
                        </Link>
                      )}
                      <button
                        className="text-button"
                        onClick={() => {
                          const local = window.prompt(
                            "Novo horário desejado (AAAA-MM-DDTHH:mm, America/Sao_Paulo):",
                          );
                          if (local) {
                            try {
                              const start = localToInstant(
                                local,
                                "America/Sao_Paulo",
                              );
                              void action(
                                "/appointments/" +
                                  a.id +
                                  "/reschedule-requests",
                                {
                                  desiredStartAt: start,
                                  desiredEndAt: new Date(
                                    new Date(start).getTime() + 50 * 60000,
                                  ).toISOString(),
                                },
                              );
                            } catch (e) {
                              setError(String(e));
                            }
                          }
                        }}
                      >
                        Remarcar
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <Empty
                title="Seu próximo encontro aparecerá aqui"
                description="Você também receberá as orientações pelo canal combinado com o consultório."
              />
            )}
          </section>
          <section className="panel portal-section">
            <div className="panel-heading">
              <h2>
                <Wallet size={19} /> Meus pagamentos
              </h2>
            </div>
            {invoices.length ? (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Vencimento</th>
                      <th>Valor</th>
                      <th>Situação</th>
                      <th>Ação</th>
                    </tr>
                  </thead>
                  <tbody>
                    {invoices.map((i) => (
                      <tr key={i.id}>
                        <td>{date(i.dueOn! + "T12:00:00Z")}</td>
                        <td>{brl(i.amountCents || 0)}</td>
                        <td>
                          <Status value={i.status!} />
                        </td>
                        <td>
                          {["OPEN", "OVERDUE", "PARTIALLY_PAID"].includes(
                            i.status!,
                          ) && (
                            <button
                              className="text-button"
                              onClick={() =>
                                action("/invoices/" + i.id + "/payments", {
                                  method: "PIX",
                                }).then((r) => {
                                  if (typeof r?.checkoutUrl === "string")
                                    window.location.assign(r.checkoutUrl);
                                })
                              }
                            >
                              Solicitar pagamento <ArrowUpRight size={14} />
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty
                title="Nenhuma cobrança disponível"
                description="Valores autorizados e suas situações aparecerão aqui."
              />
            )}
          </section>
          <div className="dashboard-columns">
            <section className="panel portal-section">
              <div className="panel-heading">
                <h2>Meus documentos</h2>
              </div>
              {documents.length ? (
                <div className="document-grid">
                  {documents.map((d) => (
                    <article className="document-card" key={d.id}>
                      <FileText size={26} />
                      <h3>{d.type}</h3>
                      <Status value={d.status!} />
                      <button
                        className="text-button"
                        onClick={() =>
                          action(
                            "/files/" + d.signedFileAssetId + "/download",
                          ).then((r) => {
                            if (typeof r?.downloadUrl === "string")
                              window.location.assign(r.downloadUrl);
                          })
                        }
                      >
                        Baixar documento <Download size={15} />
                      </button>
                    </article>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Documentos liberados pelo profissional"
                  description="Somente documentos publicados para você aparecerão neste espaço."
                />
              )}
            </section>
            <section className="panel portal-section">
              <div className="panel-heading">
                <h2>Materiais para você</h2>
              </div>
              {materials.length ? (
                <div className="document-grid">
                  {materials.map((c) => (
                    <article className="document-card" key={c.id}>
                      <h3>{c.title}</h3>
                      <div
                        className="rich-content"
                        dangerouslySetInnerHTML={{
                          __html: c.bodySanitizedHtml || "",
                        }}
                      />
                    </article>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Cuidado que acompanha"
                  description="Seu profissional pode compartilhar materiais de apoio por aqui."
                />
              )}
            </section>
          </div>
          <section className="panel settings-panel">
            <h2>Suas preferências e direitos</h2>
            <form
              className="entity-form"
              onSubmit={async (e) => {
                e.preventDefault();
                const data = new FormData(e.currentTarget);
                try {
                  const result = await request<Row>(
                    "/v1/orgs/" + org + "/portal/preferences",
                    {
                      method: "PATCH",
                      headers: { "If-Match": '"v' + prefVersion + '"' },
                      body: JSON.stringify({
                        marketingEmail: data.get("marketing") === "on",
                        noticeVersion: "communications-v1",
                        appointmentEmail: true,
                      }),
                    },
                  );
                  setPrefVersion(result.version || prefVersion + 1);
                  setMessage("Preferências atualizadas.");
                } catch (e) {
                  setError(String(e));
                }
              }}
            >
              <label className="checkbox-label">
                <input
                  name="marketing"
                  type="checkbox"
                  checked={marketing}
                  onChange={(e) => setMarketing(e.target.checked)}
                />
                Desejo receber conteúdos e novidades por email. Posso alterar
                essa escolha.
              </label>
              <SaveButton>Salvar preferências</SaveButton>
            </form>
            <div className="button-row">
              <button
                className="text-button"
                onClick={() =>
                  action("/patients/" + me.id + "/privacy-requests", {
                    type: "ACCESS",
                  })
                }
              >
                Solicitar acesso aos meus registros <ArrowUpRight size={15} />
              </button>
              <button
                className="text-button"
                onClick={() =>
                  action("/patients/" + me.id + "/privacy-requests", {
                    type: "ERASURE",
                  })
                }
              >
                Solicitar revisão de eliminação <ArrowUpRight size={15} />
              </button>
            </div>
            <Notice>
              Pedidos de dados passam por revisão, respeitando obrigações de
              guarda e vínculos legais.
            </Notice>
          </section>
        </>
      )}
    </main>
  );
}
