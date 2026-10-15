"use client";
import { useState } from "react";
import { request } from "@/lib/api.generated";
import { Field, Notice, ErrorNotice, SaveButton } from "./ui";
export function NewsletterForm({ orgSlug }: { orgSlug: string }) {
  const [busy, setBusy] = useState(false),
    [sent, setSent] = useState(false),
    [error, setError] = useState("");
  return sent ? (
    <Notice>
      Se o endereço estiver elegível, você receberá uma mensagem para confirmar
      a inscrição. Nenhum conteúdo será enviado antes da confirmação.
    </Notice>
  ) : (
    <form
      className="entity-form"
      onSubmit={async (e) => {
        e.preventDefault();
        const form = new FormData(e.currentTarget);
        setBusy(true);
        setError("");
        try {
          await request("/v1/public/newsletter/subscribe", {
            method: "POST",
            body: JSON.stringify({
              orgSlug,
              email: String(form.get("email")),
              noticeVersion: "newsletter-v1",
            }),
          });
          setSent(true);
        } catch (e) {
          setError(
            e instanceof Error ? e.message : "Não foi possível registrar.",
          );
        } finally {
          setBusy(false);
        }
      }}
    >
      <Field label="Seu email">
        <input type="email" name="email" autoComplete="email" required />
      </Field>
      <label className="checkbox-label">
        <input type="checkbox" required />
        Desejo receber os conteúdos deste consultório por email e posso cancelar
        quando quiser.
      </label>
      {error && <ErrorNotice message={error} />}
      <SaveButton busy={busy}>Receber reflexões</SaveButton>
    </form>
  );
}
