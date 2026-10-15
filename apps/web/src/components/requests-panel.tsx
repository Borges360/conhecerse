"use client";
import { useCallback, useEffect, useState } from "react";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
import { Empty, ErrorNotice, Status, date, time } from "./ui";
export function RequestsPanel({
  orgId,
  onDone,
}: {
  orgId: string;
  onDone: () => void;
}) {
  const [leads, setLeads] = useState<Row[]>([]),
    [moves, setMoves] = useState<Row[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try {
      const [l, r] = await Promise.all(
        ["/booking-requests", "/reschedule-requests"].map((path) =>
          request<{ items: Row[] }>("/v1/orgs/" + orgId + path),
        ),
      );
      setLeads(l.items);
      setMoves(r.items.filter((row) => row.status === "PENDING"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Solicitações indisponíveis.");
    }
  }, [orgId]);
  useEffect(() => {
    void load();
  }, [load]);
  async function resolve(path: string) {
    setBusy(true);
    setError("");
    try {
      await request("/v1/orgs/" + orgId + path, { method: "POST" });
      await load();
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível resolver.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Solicitações para acolher</h2>
        <span className="count-label">
          {leads.length + moves.length} pendentes
        </span>
      </div>
      {error && <ErrorNotice message={error} />}
      <div className="appointment-list">
        {leads.map((r) => (
          <div className="appointment-row" key={r.id}>
            <div className="appointment-person">
              <strong>{String(r.patientName)}</strong>
              <small>
                Primeiro contato · {date(r.startAt!)} às {time(r.startAt!)}
              </small>
            </div>
            <Status value="REQUESTED" />
            <div className="button-row">
              <button
                className="text-button"
                disabled={busy}
                onClick={() =>
                  resolve("/booking-requests/" + r.id + "/actions/accept")
                }
              >
                Aceitar e cadastrar
              </button>
              <button
                className="text-button"
                disabled={busy}
                onClick={() =>
                  resolve("/booking-requests/" + r.id + "/actions/reject")
                }
              >
                Recusar
              </button>
            </div>
          </div>
        ))}
        {moves.map((r) => (
          <div className="appointment-row" key={r.id}>
            <div className="appointment-person">
              <strong>Pedido de remarcação</strong>
              <small>
                {date(String(r.desiredStartAt))} às{" "}
                {time(String(r.desiredStartAt))}
              </small>
            </div>
            <Status value="PENDING" />
            <div className="button-row">
              <button
                className="text-button"
                disabled={busy}
                onClick={() =>
                  resolve("/reschedule-requests/" + r.id + "/actions/accept")
                }
              >
                Aceitar novo horário
              </button>
              <button
                className="text-button"
                disabled={busy}
                onClick={() =>
                  resolve("/reschedule-requests/" + r.id + "/actions/decline")
                }
              >
                Manter horário
              </button>
            </div>
          </div>
        ))}
      </div>
      {!leads.length && !moves.length && (
        <Empty
          title="Solicitações em dia"
          description="Pedidos iniciais e remarcações aparecem aqui. O horário original é preservado até o aceite."
        />
      )}
    </section>
  );
}
