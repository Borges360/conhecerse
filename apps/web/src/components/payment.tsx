"use client";
import { useEffect, useState } from "react";
import { request } from "@/lib/api.generated";
import { Brand } from "./brand";
import { Loading, Notice, ErrorNotice } from "./ui";
export function PaymentPage({ paymentId }: { paymentId: string }) {
  const [org, setOrg] = useState(""),
    [url, setUrl] = useState(""),
    [error, setError] = useState("");
  useEffect(() => {
    request<{ items: { id: string }[] }>("/v1/me/organizations")
      .then((r) => {
        const selected = new URLSearchParams(window.location.search).get("org");
        if (selected && r.items.some((o) => o.id === selected))
          setOrg(selected);
        else if (r.items.length === 1) setOrg(r.items[0].id);
        else
          setError(
            "Selecione a cobrança pelo portal do consultório correspondente.",
          );
      })
      .catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    if (!org) return;
    let stop = false;
    const check = async () => {
      try {
        const p = await request<{
          status: string;
          checkoutUrl?: string;
          failureCode?: string;
        }>("/v1/orgs/" + org + "/payments/" + paymentId);
        if (!stop && p.checkoutUrl?.startsWith("https://"))
          setUrl(p.checkoutUrl);
        if (p.status === "FAILED")
          setError(
            "A cobrança não pôde ser preparada. Entre em contato com o consultório.",
          );
      } catch (e) {
        if (!stop)
          setError(e instanceof Error ? e.message : "Cobrança indisponível.");
      }
    };
    void check();
    const timer = setInterval(check, 5000);
    return () => {
      stop = true;
      clearInterval(timer);
    };
  }, [org, paymentId]);
  return (
    <main className="onboarding-page" id="conteudo">
      <Brand />
      <h1>
        Seu pagamento,
        <br />
        <em>com segurança.</em>
      </h1>
      <Notice>
        Os dados de cartão são informados exclusivamente no checkout hospedado
        pelo provedor de pagamentos.
      </Notice>
      {error ? (
        <ErrorNotice message={error} />
      ) : url ? (
        <a className="button" href={url}>
          Continuar para o checkout seguro
        </a>
      ) : (
        <Loading />
      )}
    </main>
  );
}
