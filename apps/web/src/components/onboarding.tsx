"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowUpRight, Plus, ShieldCheck } from "lucide-react";
import { Brand } from "./brand";
import { Empty, ErrorNotice, Loading, Modal, Field, SaveButton } from "./ui";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
export function Onboarding() {
  const [orgs, setOrgs] = useState<Row[]>([]),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    request<{ items: Row[] }>("/v1/me/organizations")
      .then((r) => setOrgs(r.items))
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);
  return (
    <main className="onboarding-page" id="conteudo">
      <Brand />
      <span className="eyebrow">UM ESPAÇO PARA CADA CUIDADO</span>
      <h1>
        Bem-vinda ao
        <br />
        <em>seu consultório.</em>
      </h1>
      <p>
        Escolha o espaço que deseja acessar ou crie seu consultório. Os
        cadastros e registros de cada organização permanecem independentes.
      </p>
      {error && <ErrorNotice message={error} />}
      <div className="button-row">
        <button className="button" onClick={() => setOpen(true)}>
          <Plus size={17} />
          Criar consultório
        </button>
        <Link className="button button-outline" href="/portal">
          Ir para meu portal <ArrowUpRight size={16} />
        </Link>
      </div>
      {loading ? (
        <Loading />
      ) : orgs.length ? (
        <div className="organization-grid">
          {orgs.map((o) => (
            <article className="organization-card" key={o.id}>
              <ShieldCheck size={26} strokeWidth={1.2} />
              <h2>{o.displayName}</h2>
              <p>{o.timezone}</p>
              <Link
                href={"/app/orgs/" + o.id + "/dashboard"}
                className="text-link"
              >
                Abrir consultório <ArrowUpRight size={17} />
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title="Um novo espaço de possibilidades"
          description="Crie o seu consultório ou aceite um convite enviado para seu email verificado."
        />
      )}
      {open && (
        <Modal title="Criar seu consultório" onClose={() => setOpen(false)}>
          <form
            className="entity-form"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              try {
                const form = new FormData(e.currentTarget);
                const o = await request<Row>("/v1/orgs", {
                  method: "POST",
                  body: JSON.stringify({
                    displayName: String(form.get("name")),
                    slug: String(form.get("slug")),
                    legalPersonType: String(form.get("type")),
                    timezone: "America/Sao_Paulo",
                    ownerIsPsychologist: form.get("psychologist") === "on",
                  }),
                });
                window.location.assign("/app/orgs/" + o.id + "/configuracoes");
              } catch (e) {
                setError(
                  e instanceof Error ? e.message : "Não foi possível criar.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="form-grid">
              <Field label="Nome do consultório">
                <input name="name" required minLength={2} maxLength={160} />
              </Field>
              <Field label="Identificador público">
                <input
                  name="slug"
                  pattern="[a-z0-9-]{3,40}"
                  required
                  placeholder="meu-consultorio"
                />
              </Field>
              <Field label="Enquadramento cadastral">
                <select name="type">
                  <option value="PF">Pessoa física</option>
                  <option value="PJ">Pessoa jurídica</option>
                </select>
              </Field>
              <label className="checkbox-label">
                <input name="psychologist" type="checkbox" />
                Sou profissional de psicologia responsável
              </label>
            </div>
            {error && <ErrorNotice message={error} />}
            <div className="form-footer">
              <span>Seu email e segundo fator devem estar verificados.</span>
              <SaveButton busy={busy}>Criar meu espaço</SaveButton>
            </div>
          </form>
        </Modal>
      )}
    </main>
  );
}
