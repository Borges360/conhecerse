"use client";
import { useEffect, useState } from "react";
import { Brand } from "./brand";
import { ErrorNotice, Notice } from "./ui";
import { request } from "@/lib/api.generated";
export function Invitation({
  mode = "invite",
}: {
  mode?: "invite" | "confirm" | "unsubscribe" | "newsletter";
}) {
  const [token, setToken] = useState(""),
    [pending, setPending] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [success, setSuccess] = useState(false);
  useEffect(() => {
    setToken(
      new URLSearchParams(window.location.hash.slice(1)).get("token") || "",
    );
    window.history.replaceState(null, "", window.location.pathname);
    if (mode === "invite")
      fetch("/api/invitation", { cache: "no-store" })
        .then((r) => r.json())
        .then((r) => setPending(r.pending))
        .catch(() => {});
  }, []);
  async function accept() {
    setBusy(true);
    try {
      if (mode === "invite") {
        const response = await fetch("/api/invitation", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token: token || undefined }),
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error);
        if (result.login) {
          window.location.assign(result.login);
          return;
        }
        setSuccess(true);
        return;
      }
      const endpoint =
        mode === "confirm"
          ? "/v1/public/confirm"
          : mode === "newsletter"
            ? "/v1/public/newsletter/confirm"
            : "/v1/public/newsletter/unsubscribe";
      await request(endpoint, {
        method: "POST",
        body: JSON.stringify({ token }),
      });
      setSuccess(true);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Não foi possível validar o link.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="onboarding-page" id="conteudo">
      <Brand />
      <span className="eyebrow">SEU ESPAÇO PRIVADO</span>
      <h1>
        {mode === "invite"
          ? "Um novo vínculo."
          : mode === "confirm"
            ? "Um encontro confirmado."
            : mode === "newsletter"
              ? "Uma pausa para refletir."
              : "Suas escolhas importam."}
      </h1>
      <p>
        {mode === "invite"
          ? "O convite vincula sua conta autenticada ao cadastro do consultório. Entre com o mesmo email verificado que recebeu o convite."
          : mode === "confirm"
            ? "Confirme sua presença no encontro combinado."
            : mode === "newsletter"
              ? "Confirme sua inscrição para receber conteúdos por email. Você pode cancelar a qualquer momento."
              : "Confirme o descadastro de conteúdos de marketing."}
      </p>
      {success ? (
        <Notice>
          {mode === "invite"
            ? "Convite aceito. Seu espaço está disponível no portal."
            : mode === "confirm"
              ? "Presença confirmada com sucesso."
              : mode === "newsletter"
                ? "Inscrição confirmada."
                : "Descadastro registrado."}
        </Notice>
      ) : (
        <button
          className="button"
          disabled={(!token && !pending) || busy}
          onClick={accept}
        >
          {busy
            ? "Validando…"
            : mode === "invite"
              ? "Aceitar convite"
              : mode === "confirm"
                ? "Confirmar presença"
                : mode === "newsletter"
                  ? "Confirmar inscrição"
                  : "Confirmar descadastro"}
        </button>
      )}
      {error && <ErrorNotice message={error} />}
      <a className="text-link" href="/portal">
        Ir para meu portal
      </a>
    </main>
  );
}
