"use client";
import { X, LoaderCircle, TriangleAlert, Inbox, Check } from "lucide-react";
import { useEffect, useRef, useId, isValidElement, cloneElement } from "react";
export function Status({ value }: { value: string }) {
  const names: Record<string, string> = {
    REQUESTED: "Solicitado",
    SCHEDULED: "Agendado",
    CONFIRMED: "Confirmado",
    IN_PROGRESS: "Em atendimento",
    COMPLETED: "Concluído",
    CANCELLED: "Cancelado",
    NO_SHOW: "Não compareceu",
    OPEN: "Em aberto",
    PAID: "Pago",
    PARTIALLY_PAID: "Pagamento parcial",
    OVERDUE: "Vencido",
    DRAFT: "Rascunho",
    SIGNED: "Assinado",
    PENDING_SIGNATURE: "Aguardando assinatura",
    ISSUED: "Emitido",
    REVOKED: "Revogado",
    PENDING: "Pendente",
    ACCEPTED: "Aceito",
    DECLINED: "Recusado",
    ACTIVE: "Ativo",
    VERIFIED: "Revisado",
    RECEIPT_PENDING: "Emissão externa pendente",
    EXTERNAL_ISSUED: "Referência registrada",
    REVIEW_REQUIRED: "Revisão necessária",
    PUBLISHED: "Publicado",
    REVIEW: "Em revisão",
    ARCHIVED: "Arquivado",
    SETTLED: "Liquidado",
    GENERATED: "Gerado",
    PREPARED: "Preparado para revisão",
    RELEASED: "Liberado",
    DELIVERED: "Entregue",
    APPROVED: "Aprovado",
    REJECTED: "Rejeitado",
    DENIED: "Negado",
    SUPPRESSED: "Suprimido",
    FAILED: "Falhou",
    SENT: "Enviado",
    QUEUED: "Na fila",
    TRIAL: "Aguardando primeiro pagamento",
    PAST_DUE: "Mensalidade pendente",
    SUSPENDED: "Suspenso",
    FULFILLED: "Pedido concluído",
    IN_REVIEW: "Em revisão",
    DENIED_WITH_REASON: "Pedido negado com justificativa",
  };
  return (
    <span
      className={`status status-${value.toLowerCase().replaceAll("_", "-")}`}
    >
      <i />
      {names[value] || value}
    </span>
  );
}
export function Loading() {
  return (
    <div className="empty" role="status">
      <LoaderCircle className="spin" size={28} />
      <p>Preparando seu espaço…</p>
    </div>
  );
}
export function Empty({
  title = "Tudo começa com um primeiro passo",
  description = "Os registros aparecerão aqui assim que forem criados.",
  children,
}: {
  title?: string;
  description?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Inbox size={27} />
      </span>
      <h3>{title}</h3>
      <p>{description}</p>
      {children}
    </div>
  );
}
export function ErrorNotice({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="notice notice-error" role="alert">
      <TriangleAlert size={20} />
      <span>{message}</span>
      {message.toLowerCase().includes("segundo fator") && (
        <a
          className="text-link"
          href={
            "/api/auth/login?reauth=true&next=" +
            encodeURIComponent(
              typeof window === "undefined" ? "/app" : window.location.pathname,
            )
          }
        >
          Verificar meu acesso novamente
        </a>
      )}
      {onRetry && (
        <button className="text-button" onClick={onRetry}>
          Tentar novamente
        </button>
      )}
    </div>
  );
}
export function Notice({ children }: { children: React.ReactNode }) {
  return (
    <div className="notice">
      <span className="notice-dot" />
      {children}
    </div>
  );
}
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null),
    id = useId();
  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement as HTMLElement | null;
    dialog?.showModal();
    dialog?.querySelector<HTMLElement>("input, select, textarea")?.focus();
    return () => {
      dialog?.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby={id}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div className="modal-head">
        <div>
          <span className="eyebrow">SEU CONSULTÓRIO</span>
          <h2 id={id}>{title}</h2>
        </div>
        <button
          className="icon-button"
          aria-label="Fechar formulário"
          onClick={onClose}
        >
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: React.ReactNode;
  hint?: string;
}) {
  const generated = useId();
  const child = isValidElement<{ id?: string; "aria-describedby"?: string }>(
    children,
  )
    ? children
    : null;
  const id = child?.props.id || generated;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {child
        ? cloneElement(child, {
            id,
            "aria-describedby": hint ? id + "-hint" : undefined,
          })
        : children}
      {hint && <small id={id + "-hint"}>{hint}</small>}
    </div>
  );
}
export function SaveButton({
  busy = false,
  children = "Salvar",
}: {
  busy?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <button className="button" type="submit" disabled={busy}>
      {busy ? <LoaderCircle size={17} className="spin" /> : <Check size={17} />}{" "}
      {busy ? "Salvando…" : children}
    </button>
  );
}
export const brl = (cents: number) =>
  new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(
    cents / 100,
  );
export const date = (value: string) =>
  new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "short",
    timeZone: "America/Sao_Paulo",
  }).format(new Date(value));
export const time = (value: string) =>
  new Intl.DateTimeFormat("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "America/Sao_Paulo",
  }).format(new Date(value));
