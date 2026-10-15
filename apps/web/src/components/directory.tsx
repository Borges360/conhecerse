"use client";
import { useEffect, useState } from "react";
import { ArrowUpRight, Search, ShieldCheck } from "lucide-react";
import { request } from "@/lib/api.generated";
import { Brand } from "./brand";
import {
  Empty,
  Loading,
  ErrorNotice,
  Modal,
  Field,
  SaveButton,
  Notice,
} from "./ui";
import { localToInstant } from "@/lib/timezone";
import type { Row } from "./workspace";
export function Directory() {
  const [rows, setRows] = useState<Row[]>([]),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [sent, setSent] = useState(false);
  useEffect(() => {
    request<{ items: Row[] }>("/v1/public/professionals?limit=100")
      .then((r) => setRows(r.items))
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);
  return (
    <main className="directory-page" id="conteudo">
      <div className="portal-top">
        <Brand />
        <a className="text-link" href="/login">
          Meu espaço <ArrowUpRight size={17} />
        </a>
      </div>
      <span className="eyebrow">UM ENCONTRO COM POSSIBILIDADES</span>
      <h1>
        Encontre um espaço
        <br />
        <em>para ser você.</em>
      </h1>
      <p>
        Perfis publicados com consentimento e registro profissional revisado
        pelo consultório. Cada primeiro encontro começa com uma solicitação.
      </p>
      <div className="search-box">
        <Search size={18} />
        <input
          aria-label="Buscar profissional"
          placeholder="Buscar pelo nome…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      {error && <ErrorNotice message={error} />}{" "}
      {sent && (
        <Notice>
          Sua solicitação foi registrada. O consultório entrará em contato pelo
          canal informado para confirmar a disponibilidade.
        </Notice>
      )}
      {loading ? (
        <Loading />
      ) : rows.length ? (
        <div className="directory-grid">
          {rows
            .filter((r) =>
              (r.displayName || "")
                .toLowerCase()
                .includes(search.toLowerCase()),
            )
            .map((p) => (
              <article key={p.id} className="public-directory-card">
                <span className="patient-avatar large">
                  {p.displayName?.slice(0, 1)}
                </span>
                <h2>{p.displayName}</h2>
                {Boolean(p.publicBio) && <p>{String(p.publicBio)}</p>}
                {Array.isArray(p.languages) && p.languages.length > 0 && (
                  <p>Idiomas: {p.languages.join(", ")}</p>
                )}
                {Array.isArray(p.specialties) && p.specialties.length > 0 && (
                  <p>
                    Especialidades autodeclaradas: {p.specialties.join(", ")}
                  </p>
                )}
                {Array.isArray(p.reviewedSpecialties) &&
                  p.reviewedSpecialties.length > 0 && (
                    <p>
                      Especialidades revisadas:{" "}
                      {p.reviewedSpecialties.join(", ")}
                    </p>
                  )}
                {Array.isArray(p.appointmentTypes) && (
                  <p>
                    {p.appointmentTypes
                      .map((t) => (t === "REMOTE" ? "Online" : "Presencial"))
                      .join(" · ")}
                  </p>
                )}
                <p>
                  CRP {p.crpUf} / {p.crpNumber}
                </p>
                <p>
                  <ShieldCheck size={14} /> Perfil revisado pelo consultório
                </p>
                <button className="button" onClick={() => setSelected(p)}>
                  Solicitar primeiro encontro <ArrowUpRight size={17} />
                </button>
              </article>
            ))}
        </div>
      ) : (
        <Empty
          title="Novos caminhos estão sendo preparados"
          description="Não há perfis públicos disponíveis neste momento. O consultório publica a agenda após revisar o registro e a disponibilidade."
        />
      )}
      {selected && (
        <Modal
          title={"Solicitar encontro com " + selected.displayName}
          onClose={() => setSelected(null)}
        >
          <form
            className="entity-form"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              try {
                const data = new FormData(e.currentTarget),
                  start = localToInstant(
                    String(data.get("start")),
                    "America/Sao_Paulo",
                  );
                await request("/v1/public/booking-requests", {
                  method: "POST",
                  body: JSON.stringify({
                    professionalId: selected.id,
                    patientName: String(data.get("name")),
                    patientEmail: String(data.get("email")),
                    startAt: start,
                    endAt: new Date(
                      new Date(start).getTime() + 50 * 60000,
                    ).toISOString(),
                    noticeVersion: "public-booking-v1",
                  }),
                });
                setSent(true);
                setSelected(null);
              } catch (e) {
                setError(
                  e instanceof Error
                    ? e.message
                    : "Não foi possível solicitar.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="form-grid">
              <Field label="Seu nome">
                <input name="name" required minLength={2} />
              </Field>
              <Field label="Email para contato">
                <input name="email" type="email" required />
              </Field>
              <Field label="Horário desejado · America/Sao_Paulo">
                <input name="start" type="datetime-local" required />
              </Field>
              <label className="checkbox-label">
                <input type="checkbox" required />
                Li o aviso de privacidade e desejo receber contato para esta
                solicitação.
              </label>
            </div>
            <Notice>
              A solicitação não confirma a sessão. O consultório fará a
              avaliação e o contato inicial.
            </Notice>
            {error && <ErrorNotice message={error} />}
            <SaveButton busy={busy}>Enviar solicitação</SaveButton>
          </form>
        </Modal>
      )}
    </main>
  );
}
