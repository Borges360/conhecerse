"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowRight,
  ArrowUpRight,
  Plus,
  CalendarDays,
  Check,
  ChevronLeft,
  ChevronRight,
  Search,
  Video,
  MapPin,
  Clock,
  Users,
  Wallet,
  MoreHorizontal,
  FileText,
  ExternalLink,
  Copy,
  ShieldCheck,
  BookOpen,
  Download,
  RefreshCw,
} from "lucide-react";
import { MonthCalendar, monthAnchor } from "./month-calendar";
import { ClinicalFiles } from "./clinical-files";
import { RequestsPanel } from "./requests-panel";
import { PatientDetails } from "./patient-details";
import { CareWorkflowsPanel } from "./care-workflows";
import { CompletionSettings } from "./completion-settings";
import { Shell } from "./shell";
import { Dandelion } from "./brand";
import {
  Status,
  Loading,
  Empty,
  ErrorNotice,
  Notice,
  Modal,
  Field,
  SaveButton,
  brl,
  date,
  time,
} from "./ui";
import { checksum } from "@/lib/browser-crypto";
import { request, ApiError } from "@/lib/api.generated";
import {
  demoPatients,
  demoProfessional,
} from "@/lib/demo";
import { localToInstant, moneyToCents } from "@/lib/timezone";

export type Row = {
  id: string;
  version?: number;
  displayName?: string;
  patientId?: string;
  professionalId?: string;
  status?: string;
  startAt?: string;
  endAt?: string;
  modality?: string;
  priceCents?: number;
  amountCents?: number;
  paidCents?: number;
  payerId?: string;
  dueOn?: string;
  title?: string;
  entryType?: string;
  type?: string;
  noteType?: string;
  narrative?: string;
  plan?: string;
  referrals?: string;
  occurredAt?: string;
  signedAt?: string;
  bodySanitizedHtml?: string;
  slug?: string;
  visibility?: string;
  officialReceiptId?: string;
  externalIssuedOn?: string;
  paymentId?: string;
  verificationStatus?: string;
  crpUf?: string;
  crpNumber?: string;
  assignedProfessionals?: string[];
  fileAssetId?: string;
  localDate?: string;
  timezone?: string;
  settingsVersion?: number;
  [key: string]: unknown;
};
type Page = { items: Row[]; nextCursor: string | null };
type ModalKind =
  | "patient"
  | "appointment"
  | "invoice"
  | "payer"
  | "document"
  | "content"
  | "receipt"
  | "professional"
  | "availability"
  | "price"
  | "invite"
  | "template"
  | "integration"
  | "approval"
  | null;
const titles: Record<string, [string, string]> = {
  dashboard: [
    "Seu cuidado começa por aqui.",
    "Um olhar tranquilo para o seu dia e para o seu consultório.",
  ],
  agenda: [
    "Tempo para cada encontro.",
    "Organize seus atendimentos e acolha os movimentos da semana.",
  ],
  pacientes: [
    "Cada história importa.",
    "Vínculos, informações e cuidado em um só lugar.",
  ],
  prontuario: [
    "Um espaço para a escuta.",
    "Evoluções e registros protegidos, com acesso por vínculo assistencial.",
  ],
  financeiro: [
    "Clareza para cuidar.",
    "Acompanhe faturas, recebimentos e pendências do consultório.",
  ],
  recibos: [
    "Receita Saúde, com cuidado.",
    "Organize recebimentos e registre referências de recibos emitidos no serviço oficial.",
  ],
  documentos: [
    "Cuidado também nos registros.",
    "Prepare, revise e disponibilize documentos com assinatura externa.",
  ],
  conteudos: [
    "O cuidado pode ir além.",
    "Materiais, reflexões e conteúdos para compartilhar.",
  ],
  configuracoes: [
    "Seu jeito de cuidar.",
    "Equipe, disponibilidade, políticas e conexões do seu consultório.",
  ],
};

export function Workspace({
  orgId,
  section,
  demo = false,
  name,
  patientId,
  seededDemoAppointments = [],
  seededDemoInvoices = [],
}: {
  orgId: string;
  section: string;
  demo?: boolean;
  name?: string;
  patientId?: string;
  seededDemoAppointments?: Row[];
  seededDemoInvoices?: Row[];
}) {
  const [patients, setPatients] = useState<Row[]>(demo ? demoPatients : []),
    [appointments, setAppointments] = useState<Row[]>(
      demo ? seededDemoAppointments : [],
    ),
    [professionals, setProfessionals] = useState<Row[]>(
      demo ? [demoProfessional] : [],
    ),
    [invoices, setInvoices] = useState<Row[]>(demo ? seededDemoInvoices : []),
    [rows, setRows] = useState<Row[]>([]),
    [payers, setPayers] = useState<Row[]>([]),
    [organization, setOrganization] = useState<Row | null>(null),
    [loading, setLoading] = useState(!demo),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [modal, setModal] = useState<ModalKind>(null),
    [selected, setSelected] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [search, setSearch] = useState(""),
    [week, setWeek] = useState(0),
    [view, setView] = useState<"week" | "month" | "list">("week"),
    [month, setMonth] = useState(0),
    [patientCursor, setPatientCursor] = useState(""),
    [patientNext, setPatientNext] = useState<string | null>(null),
    [queryName, setQueryName] = useState(""),
    [appointmentNext, setAppointmentNext] = useState<string | null>(null),
    [appointmentQuery, setAppointmentQuery] = useState(""),
    [invoiceNext, setInvoiceNext] = useState<string | null>(null),
    [financialStats, setFinancialStats] = useState<Row[]>([]),
    [financeQuery, setFinanceQuery] = useState(""),
    [filter, setFilter] = useState("all"),
    [tab, setTab] = useState("geral");
  const base = "/v1/orgs/" + orgId,
    route = demo ? "/demonstracao" : "/app/orgs/" + orgId;
  const load = useCallback(async () => {
    if (demo) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError("");
    const start = view === "month" ? monthAnchor(month) : weekDates(week)[0];
    start.setHours(0, 0, 0, 0);
    if (view === "month")
      start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
    const end = new Date(start);
    end.setDate(
      end.getDate() + (view === "month" ? 42 : section === "agenda" ? 7 : 30),
    );
    const apQuery =
      "?limit=100&from=" +
      encodeURIComponent(start.toISOString()) +
      "&to=" +
      encodeURIComponent(end.toISOString());
    setAppointmentQuery(apQuery);
    const resources = [
      ["org", base],
      [
        "patients",
        base +
          "/patients?limit=25" +
          (patientCursor
            ? "&cursor=" + encodeURIComponent(patientCursor)
            : "") +
          (queryName ? "&q=" + encodeURIComponent(queryName) : ""),
      ],
      ["professionals", base + "/professionals?limit=100"],
      ["appointments", base + "/appointments" + apQuery],
    ] as const;
    const results = await Promise.allSettled(
      resources.map(async ([key, url]) => ({
        key,
        data: await request<Page & Row>(url),
      })),
    );
    for (const result of results)
      if (result.status === "fulfilled") {
        const { key, data } = result.value;
        if (key === "org") setOrganization(data);
        if (key === "patients") {
          setPatients(data.items);
          setPatientNext(data.nextCursor);
        }
        if (key === "professionals") setProfessionals(data.items);
        if (key === "appointments") {
          setAppointments(data.items);
          setAppointmentNext(data.nextCursor);
        }
      }
    const endpoints: Record<string, string> = {
      financeiro: "/invoices",
      recibos: "/receipt-references",
      documentos: "/documents",
      conteudos: "/content",
      configuracoes:
        tab === "equipe"
          ? "/members"
          : tab === "integracoes"
            ? "/integrations"
            : tab === "homologacoes"
              ? "/release-approvals"
              : tab === "modelos"
                ? "/document-templates"
                : "/settings",
    };
    const suffix = endpoints[section];
    if (suffix) {
      try {
        const data = await request<Page & Row>(
          base +
            suffix +
            "?limit=100" +
            (section === "financeiro" ? financeQuery : ""),
        );
        setRows(data.items || [data]);
        if (section === "financeiro") {
          setInvoices(data.items);
          setInvoiceNext(data.nextCursor);
          setFinancialStats(
            (
              await request<Page>(
                base + "/financial-reports?limit=100" + financeQuery,
              )
            ).items,
          );
          try {
            const p = await request<Page>(base + "/payers");
            setPayers(p.items);
          } catch {}
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : "Não foi possível carregar.");
      }
    }
    const orgFailure = results[0];
    if (orgFailure.status === "rejected")
      setError(
        orgFailure.reason instanceof Error
          ? orgFailure.reason.message
          : "O consultório não está disponível.",
      );
    setLoading(false);
  }, [
    base,
    demo,
    section,
    tab,
    week,
    view,
    month,
    patientCursor,
    queryName,
    financeQuery,
  ]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    setSelected(null);
    setSearch("");
    setError("");
    setNotice("");
  }, [section]);
  useEffect(() => {
    if (section === "pacientes" && patientId) {
      if (demo) {
        const p = patients.find((r) => r.id === patientId);
        if (p) setSelected(p);
      } else
        request<Row>(base + "/patients/" + patientId)
          .then(setSelected)
          .catch((e) =>
            setError(e instanceof Error ? e.message : "Cadastro indisponível."),
          );
    }
  }, [section, patientId, base, demo]);
  useEffect(() => {
    if (demo) return;
    const timer = setTimeout(() => {
      setPatientCursor("");
      setQueryName(search.trim());
    }, 300);
    return () => clearTimeout(timer);
  }, [search, demo]);
  const billed = demo
      ? invoices.reduce((sum, r) => sum + (r.amountCents || 0), 0)
      : financialStats.reduce(
          (sum, r) => sum + Number(r.invoicedCents || 0),
          0,
        ),
    received = demo
      ? invoices.reduce((sum, r) => sum + (r.paidCents || 0), 0)
      : financialStats.reduce(
          (sum, r) => sum + Number(r.receivedCents || 0),
          0,
        ),
    openAmount = demo
      ? invoices.reduce(
          (sum, r) => sum + (r.amountCents || 0) - (r.paidCents || 0),
          0,
        )
      : financialStats
          .filter((r) =>
            ["OPEN", "OVERDUE", "PARTIALLY_PAID"].includes(r.status!),
          )
          .reduce(
            (sum, r) =>
              sum + Number(r.invoicedCents || 0) - Number(r.receivedCents || 0),
            0,
          );
  async function mutate(
    path: string,
    body?: unknown,
    method = "POST",
    version?: number,
  ) {
    if (demo) {
      setNotice(
        "Esta demonstração usa dados fictícios. Entre em uma conta para registrar alterações.",
      );
      return null;
    }
    setBusy(true);
    setError("");
    try {
      const result = await request<Row>(base + path, {
        method,
        headers: version ? { "If-Match": '"v' + version + '"' } : undefined,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      setNotice("Atualização registrada com sucesso.");
      await load();
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível concluir.");
      return null;
    } finally {
      setBusy(false);
    }
  }
  async function submit(
    kind: Exclude<ModalKind, null>,
    values: Record<string, string>,
  ) {
    let path = "",
      body: Record<string, unknown> = { ...values };
    const clean = (key: string) => {
      if (!values[key]) delete body[key];
    };
    switch (kind) {
      case "patient":
        path = "/patients";
        [
          "email",
          "phone",
          "birthDate",
          "professionalId",
          "cpf",
          "socialName",
        ].forEach(clean);
        break;
      case "appointment":
        path = "/appointments";
        body = {
          patientId: values.patientId,
          professionalId: values.professionalId,
          startAt: localToInstant(
            values.startAt,
            organization?.timezone || "America/Sao_Paulo",
          ),
          endAt: localToInstant(
            values.endAt,
            organization?.timezone || "America/Sao_Paulo",
          ),
          modality: values.modality,
          priceCents: moneyToCents(values.price),
        };
        break;
      case "payer":
        path = "/payers";
        ["cpfCnpj", "patientId"].forEach(clean);
        break;
      case "invoice":
        path = "/invoices";
        body = {
          payerId: values.payerId,
          dueOn: values.dueOn,
          items: [
            {
              descriptionCode: "SESSION_PRIMARY",
              amountCents: moneyToCents(values.price),
              ...(values.appointmentId
                ? { appointmentId: values.appointmentId }
                : {}),
            },
          ],
        };
        break;
      case "document":
        path = "/documents";
        break;
      case "content":
        path = "/content";
        clean("slug");
        break;
      case "receipt":
        path = "/receipt-references/" + selected?.id + "/actions/register";
        body = {
          officialReceiptId: values.officialReceiptId,
          externalIssuedOn: values.externalIssuedOn,
          amountCents: selected?.amountCents,
          payerId: selected?.payerId,
        };
        break;
      case "professional":
        path = "/professionals";
        body = {
          ...body,
          publicProfileEnabled: values.publicProfileEnabled === "true",
        };
        break;
      case "availability":
        path = "/professionals/" + values.professionalId + "/availability";
        delete body.professionalId;
        body.weekday = Number(values.weekday);
        break;
      case "price":
        path = "/professionals/" + values.professionalId + "/prices";
        body = {
          modality: values.modality,
          amountCents: moneyToCents(values.price),
          effectiveFrom: values.effectiveFrom,
        };
        break;
      case "invite":
        path = "/members/invitations";
        body = { email: values.email, roles: [values.role] };
        break;
      case "template":
        path = "/document-templates";
        break;
      case "integration":
        path = "/integrations";
        break;
      case "approval":
        path = "/release-approvals";
        break;
    }
    const result = await mutate(path, body);
    if (result || demo) setModal(null);
  }
  const matchingPatients = demo
    ? patients.filter((p) =>
        (p.displayName || "").toLowerCase().includes(search.toLowerCase()),
      )
    : patients;
  const patientName = (id?: string) =>
    patients.find((p) => p.id === id)?.displayName ||
    "Paciente com acesso restrito";
  const dayFormat = new Intl.DateTimeFormat("pt-BR", {
    weekday: "long",
    day: "numeric",
    month: "long",
    timeZone: "America/Sao_Paulo",
  });
  const today = new Date(),
    todayISO = new Intl.DateTimeFormat("sv-SE", {
      timeZone: "America/Sao_Paulo",
    }).format(today);
  const todays = appointments.filter(
    (a) =>
      a.startAt &&
      new Intl.DateTimeFormat("sv-SE", {
        timeZone: "America/Sao_Paulo",
      }).format(new Date(a.startAt)) === todayISO,
  );
  const [title, description] = titles[section] || titles.dashboard;
  function newButton(kind: Exclude<ModalKind, null>, label: string) {
    return (
      <button
        className="button"
        onClick={() => {
          setSelected(null);
          setModal(kind);
        }}
      >
        <Plus size={18} />
        {label}
      </button>
    );
  }
  const heading = (
    <div className="page-heading">
      <div>
        <span className="eyebrow">
          {section === "dashboard"
            ? dayFormat.format(today).toUpperCase()
            : "MEU CONSULTÓRIO · " +
              (organization?.timezone || "AMERICA/SAO_PAULO")}
        </span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {section === "agenda"
        ? newButton("appointment", "Novo atendimento")
        : section === "pacientes"
          ? newButton("patient", "Novo paciente")
          : section === "financeiro"
            ? newButton("invoice", "Nova fatura")
            : section === "documentos"
              ? newButton("document", "Novo documento")
              : section === "conteudos"
                ? newButton("content", "Novo conteúdo")
                : null}
    </div>
  );
  const appointmentList = (list: Row[], compact = false) => (
    <div className="appointment-list">
      {list.length ? (
        list.map((a) => (
          <button
            key={a.id}
            className="appointment-row"
            onClick={() => setSelected(a)}
          >
            <span className="appointment-time">
              {time(a.startAt!)}
              <small>{time(a.endAt!)}</small>
            </span>
            <span
              className={
                "appointment-stripe " +
                (a.modality === "IN_PERSON" ? "stripe-sand" : "")
              }
            />
            <span className="patient-avatar">
              {patientName(a.patientId)
                .split(" ")
                .map((n) => n[0])
                .slice(0, 2)
                .join("")}
            </span>
            <span className="appointment-person">
              <strong>{patientName(a.patientId)}</strong>
              <small>
                {a.modality === "REMOTE" ? (
                  <Video size={12} />
                ) : (
                  <MapPin size={12} />
                )}{" "}
                {a.modality === "REMOTE" ? "Atendimento online" : "Presencial"}{" "}
                · 50 min
              </small>
            </span>
            <Status value={a.status!} />
            {!compact && <ArrowUpRight size={16} />}
          </button>
        ))
      ) : (
        <Empty
          title="Tempo livre para acolher"
          description="Você não tem atendimentos neste período."
        />
      )}
    </div>
  );
  return (
    <Shell
      orgId={orgId}
      section={section}
      demo={demo}
      name={name || organization?.displayName || "Meu consultório"}
    >
      {heading}
      {error && <ErrorNotice message={error} onRetry={load} />}{" "}
      {notice && (
        <div className="notice notice-success" role="status">
          <Check size={17} />
          {notice}
          <button className="text-button" onClick={() => setNotice("")}>
            Fechar
          </button>
        </div>
      )}
      {loading ? (
        <Loading />
      ) : (
        <>
          {section === "dashboard" && (
            <>
              <div className="dashboard-welcome">
                <div>
                  <span className="eyebrow">PRESENÇA EM CADA ENCONTRO</span>
                  <h2>
                    Um dia de cada vez.
                    <br />
                    <em>Um cuidado por inteiro.</em>
                  </h2>
                  <p>Seu tempo organizado. Seu olhar onde mais importa.</p>
                  <Link className="text-link" href={route + "/agenda"}>
                    Ver minha agenda <ArrowRight size={16} />
                  </Link>
                </div>
                <Dandelion size={155} />
                <div className="welcome-date">
                  <span>
                    {new Intl.DateTimeFormat("pt-BR", {
                      day: "2-digit",
                      timeZone: "America/Sao_Paulo",
                    }).format(today)}
                  </span>
                  <small>
                    {new Intl.DateTimeFormat("pt-BR", {
                      month: "long",
                      timeZone: "America/Sao_Paulo",
                    }).format(today)}
                  </small>
                </div>
              </div>
              <div className="stats-grid">
                <Stat
                  icon={<CalendarDays size={21} />}
                  label="Atendimentos hoje"
                  value={String(todays.length)}
                  hint="Seu tempo para acolher"
                />
                <Stat
                  icon={<Check size={21} />}
                  label="Presenças confirmadas"
                  value={String(
                    todays.filter((a) => a.status === "CONFIRMED").length,
                  )}
                  hint="Encontros confirmados para hoje"
                />
                <Stat
                  icon={<Users size={21} />}
                  label="Pacientes em cuidado"
                  value={String(patients.length)}
                  hint="Vínculos ativos no consultório"
                />
                <Stat
                  icon={<Wallet size={21} />}
                  label="Faturas em aberto"
                  value={brl(
                    invoices
                      .filter((i) => i.status === "OPEN")
                      .reduce(
                        (s, i) => s + (i.amountCents || 0) - (i.paidCents || 0),
                        0,
                      ),
                  )}
                  hint={
                    demo ? "Valores demonstrativos" : "Consulte seu financeiro"
                  }
                />
              </div>
              <div className="dashboard-columns">
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <span className="eyebrow">SEU DIA, COM PRESENÇA</span>
                      <h2>Próximos encontros</h2>
                    </div>
                    <Link href={route + "/agenda"} className="text-link">
                      Ver agenda <ArrowUpRight size={15} />
                    </Link>
                  </div>
                  {appointmentList(
                    todays.length
                      ? todays
                      : appointments
                          .filter((a) => new Date(a.startAt!) > today)
                          .slice(0, 4),
                    true,
                  )}
                </section>
                <aside className="dashboard-side">
                  <section className="panel attention-panel">
                    <span className="eyebrow">UM OLHAR COM ATENÇÃO</span>
                    <h2>Para acompanhar</h2>
                    <Link href={route + "/agenda"}>
                      <span className="attention-icon">
                        <CalendarDays size={18} />
                      </span>
                      <span>
                        <strong>Confirmações da agenda</strong>
                        <small>
                          {
                            appointments.filter((a) => a.status === "SCHEDULED")
                              .length
                          }{" "}
                          encontros aguardando confirmação
                        </small>
                      </span>
                      <ChevronRight size={16} />
                    </Link>
                    <Link href={route + "/recibos"}>
                      <span className="attention-icon sand">
                        <ReceiptIcon />
                      </span>
                      <span>
                        <strong>Receita Saúde</strong>
                        <small>Confira as referências de recebimento</small>
                      </span>
                      <ChevronRight size={16} />
                    </Link>
                    <Link href={route + "/documentos"}>
                      <span className="attention-icon blue">
                        <FileText size={18} />
                      </span>
                      <span>
                        <strong>Documentos do cuidado</strong>
                        <small>Revisões e assinaturas externas</small>
                      </span>
                      <ChevronRight size={16} />
                    </Link>
                  </section>
                  <section className="quote-panel">
                    <Dandelion size={54} />
                    <p>
                      “Crises também podem ser
                      <br />
                      <em>pontos de virada.</em>”
                    </p>
                    <span>UM LEMBRETE PARA O SEU DIA</span>
                  </section>
                </aside>
              </div>
            </>
          )}
          {section === "agenda" && (
            <section className="panel agenda-panel">
              <div className="agenda-toolbar">
                <div className="date-navigator">
                  <button
                    className="icon-button"
                    aria-label="Semana anterior"
                    onClick={() =>
                      view === "month" ? setMonth(month - 1) : setWeek(week - 1)
                    }
                  >
                    <ChevronLeft size={18} />
                  </button>
                  <strong>
                    {view === "month"
                      ? new Intl.DateTimeFormat("pt-BR", {
                          month: "long",
                          year: "numeric",
                        }).format(monthAnchor(month))
                      : weekLabel(week)}
                  </strong>
                  <button
                    className="icon-button"
                    aria-label="Próxima semana"
                    onClick={() =>
                      view === "month" ? setMonth(month + 1) : setWeek(week + 1)
                    }
                  >
                    <ChevronRight size={18} />
                  </button>
                  <button
                    className="button button-outline small"
                    onClick={() => {
                      setWeek(0);
                      setMonth(0);
                    }}
                  >
                    Hoje
                  </button>
                </div>
                <div className="agenda-filters">
                  <select
                    aria-label="Filtrar atendimentos"
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
                  >
                    <option value="all">Todos os atendimentos</option>
                    <option value="CONFIRMED">Confirmados</option>
                    <option value="SCHEDULED">Agendados</option>
                    <option value="REQUESTED">Solicitados</option>
                  </select>
                  <div className="segmented">
                    <button
                      className={view === "week" ? "selected" : ""}
                      onClick={() => setView("week")}
                    >
                      Semana
                    </button>
                    <button
                      className={view === "month" ? "selected" : ""}
                      onClick={() => setView("month")}
                    >
                      Mês
                    </button>
                    <button
                      className={view === "list" ? "selected" : ""}
                      onClick={() => setView("list")}
                    >
                      Lista
                    </button>
                  </div>
                </div>
              </div>
              {view === "week" ? (
                <WeekCalendar
                  week={week}
                  appointments={appointments.filter(
                    (a) => filter === "all" || a.status === filter,
                  )}
                  patientName={patientName}
                  onSelect={setSelected}
                />
              ) : view === "month" ? (
                <MonthCalendar
                  offset={month}
                  appointments={appointments.filter(
                    (a) => filter === "all" || a.status === filter,
                  )}
                  patientName={patientName}
                  onSelect={setSelected}
                />
              ) : (
                appointmentList(
                  appointments.filter(
                    (a) => filter === "all" || a.status === filter,
                  ),
                )
              )}
              {appointmentNext && !demo && (
                <button
                  className="button button-outline"
                  onClick={async () => {
                    const page = await request<Page>(
                      base +
                        "/appointments" +
                        appointmentQuery +
                        "&cursor=" +
                        encodeURIComponent(appointmentNext),
                    );
                    setAppointments((rows) => [...rows, ...page.items]);
                    setAppointmentNext(page.nextCursor);
                  }}
                >
                  Carregar mais atendimentos do período
                </button>
              )}
              <div className="agenda-legend">
                <span>
                  <i className="legend-blue" />
                  Confirmado
                </span>
                <span>
                  <i className="legend-sand" />
                  Agendado
                </span>
                <span>
                  <i className="legend-outline" />
                  Solicitado
                </span>
                <small>
                  Horários em {organization?.timezone || "America/Sao_Paulo"}
                </small>
              </div>
            </section>
          )}
          {section === "agenda" && !demo && (
            <RequestsPanel orgId={orgId} onDone={() => void load()} />
          )}
          {section === "pacientes" && (
            <section className="panel">
              <div className="table-toolbar">
                <div className="search-box">
                  <Search size={17} />
                  <input
                    aria-label="Buscar pacientes"
                    placeholder={
                      demo ? "Buscar pelo nome…" : "Buscar pelo nome completo…"
                    }
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                </div>
                <span className="count-label">
                  {matchingPatients.length} pacientes neste consultório
                </span>
              </div>
              {matchingPatients.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Paciente</th>
                        <th>Situação</th>
                        <th>Vínculo assistencial</th>
                        <th>
                          <span className="sr-only">Ações</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {matchingPatients.map((p) => (
                        <tr key={p.id}>
                          <td>
                            <Link
                              href={route + "/pacientes/" + p.id}
                              className="patient-cell"
                            >
                              <span className="patient-avatar">
                                {p.displayName
                                  ?.split(" ")
                                  .map((n) => n[0])
                                  .slice(0, 2)
                                  .join("")}
                              </span>
                              <span>
                                <strong>{p.displayName}</strong>
                                <small>Cadastro privado do consultório</small>
                              </span>
                            </Link>
                          </td>
                          <td>
                            <Status value={p.status || "ACTIVE"} />
                          </td>
                          <td>
                            {p.assignedProfessionals?.length
                              ? `${p.assignedProfessionals.length} profissional(is)`
                              : "Sem vínculo clínico"}
                          </td>
                          <td>
                            <button
                              className="icon-button"
                              aria-label={"Ver cadastro de " + p.displayName}
                              onClick={() => setSelected(p)}
                            >
                              <ArrowUpRight size={18} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title={
                    search
                      ? "Nenhum paciente encontrado"
                      : "Seu primeiro vínculo começa aqui"
                  }
                  description="Cadastre um paciente e defina o profissional responsável pelo cuidado."
                >
                  {newButton("patient", "Cadastrar paciente")}
                </Empty>
              )}
            </section>
          )}
          {section === "pacientes" && !demo && (
            <div className="button-row">
              {patientCursor && (
                <button
                  className="button button-outline"
                  onClick={() => setPatientCursor("")}
                >
                  Voltar ao início da lista
                </button>
              )}
              {patientNext && (
                <button
                  className="button button-outline"
                  onClick={() => setPatientCursor(patientNext)}
                >
                  Próxima página de pacientes
                </button>
              )}
            </div>
          )}
          {section === "prontuario" && (
            <ClinicalPanel
              orgId={orgId}
              patients={patients}
              initialPatient={patientId}
              demo={demo}
              onNotice={setNotice}
            />
          )}
          {section === "financeiro" && (
            <>
              {!demo && (
                <form
                  className="panel table-toolbar"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const data = new FormData(e.currentTarget),
                      params = new URLSearchParams();
                    for (const field of [
                      "from",
                      "to",
                      "status",
                      "professionalId",
                      "method",
                    ]) {
                      const value = String(data.get(field) || "");
                      if (value) params.set(field, value);
                    }
                    setFinanceQuery("&" + params.toString());
                  }}
                >
                  <Field label="Vencimento desde">
                    <input name="from" type="date" />
                  </Field>
                  <Field label="Vencimento até">
                    <input name="to" type="date" />
                  </Field>
                  <Field label="Situação">
                    <select name="status">
                      <option value="">Todas</option>
                      {[
                        "DRAFT",
                        "OPEN",
                        "PAID",
                        "PARTIALLY_PAID",
                        "OVERDUE",
                        "CANCELLED",
                        "REFUNDED",
                        "DISPUTED",
                      ].map((status) => (
                        <option key={status}>{status}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Profissional">
                    <select name="professionalId">
                      <option value="">Todos</option>
                      {professionals.map((p) => (
                        <option value={p.id} key={p.id}>
                          {p.displayName}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Pagamento">
                    <select name="method">
                      <option value="">Todos</option>
                      {["PIX", "BOLETO", "CREDIT_CARD", "MANUAL"].map(
                        (method) => (
                          <option key={method}>{method}</option>
                        ),
                      )}
                    </select>
                  </Field>
                  <button className="button small" type="submit">
                    Aplicar filtros
                  </button>
                </form>
              )}
              <div className="stats-grid three">
                <Stat
                  icon={<Wallet size={20} />}
                  label="Total em faturas"
                  value={brl(billed)}
                  hint="Todas as páginas no filtro selecionado"
                />
                <Stat
                  icon={<Check size={20} />}
                  label="Recebido"
                  value={brl(received)}
                  hint="Recebimentos conciliados"
                />
                <Stat
                  icon={<Clock size={20} />}
                  label="Em aberto"
                  value={brl(openAmount)}
                  hint="Saldo a receber"
                />
              </div>
              <section className="panel">
                <div className="panel-heading">
                  <h2>Faturas e recebimentos</h2>
                  <div className="button-row">
                    <button
                      className="button button-outline small"
                      onClick={() => setModal("payer")}
                    >
                      <Plus size={15} /> Novo pagador
                    </button>
                    <button
                      className="text-button"
                      onClick={() => exportCsv(invoices)}
                    >
                      Exportar CSV <Download size={15} />
                    </button>
                  </div>
                </div>
                {invoices.length ? (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Fatura</th>
                          <th>Vencimento</th>
                          <th>Valor</th>
                          <th>Recebido</th>
                          <th>Situação</th>
                          <th>Ações</th>
                        </tr>
                      </thead>
                      <tbody>
                        {invoices.map((i) => (
                          <tr key={i.id}>
                            <td>#{i.id.slice(0, 8)}</td>
                            <td>{date(i.dueOn! + "T12:00:00Z")}</td>
                            <td>{brl(i.amountCents || 0)}</td>
                            <td>{brl(i.paidCents || 0)}</td>
                            <td>
                              <Status value={i.status!} />
                            </td>
                            <td>
                              <button
                                className="text-button"
                                onClick={() => setSelected(i)}
                              >
                                Ver fatura <ArrowUpRight size={14} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <Empty
                    title="Seu financeiro, com clareza"
                    description="Cadastre o pagador e registre a primeira fatura."
                  />
                )}
              </section>
              {invoiceNext && !demo && (
                <button
                  className="button button-outline"
                  onClick={async () => {
                    const page = await request<Page>(
                      base +
                        "/invoices?limit=100" +
                        financeQuery +
                        "&cursor=" +
                        encodeURIComponent(invoiceNext),
                    );
                    setInvoices((rows) => [...rows, ...page.items]);
                    setInvoiceNext(page.nextCursor);
                  }}
                >
                  Carregar mais faturas
                </button>
              )}
              <Notice>
                Pagamentos online ficam disponíveis após habilitação e
                homologação da conta do consultório no PSP. Lançamentos manuais
                exigem justificativa e ficam auditados.
              </Notice>
            </>
          )}
          {section === "recibos" && (
            <>
              <div className="receipt-guide">
                <span className="receipt-guide-icon">
                  <ReceiptIcon />
                </span>
                <div>
                  <span className="eyebrow">
                    EMISSÃO ASSISTIDA, NO CANAL OFICIAL
                  </span>
                  <h2>Um passo de cada vez, com clareza.</h2>
                  <p>
                    Emita o recibo no serviço oficial da Receita Saúde. Depois,
                    registre aqui o identificador e a data. A plataforma mantém
                    a referência e a declaração do profissional.
                  </p>
                </div>
                <a
                  className="button button-outline"
                  href="https://www.gov.br/receitafederal/pt-br/assuntos/receita-saude"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Abrir site oficial <ExternalLink size={16} />
                </a>
              </div>
              <section className="panel">
                <div className="panel-heading">
                  <h2>Recebimentos para acompanhar</h2>
                  <span className="count-label">{rows.length} referências</span>
                </div>
                {rows.length ? (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Paciente</th>
                          <th>Recebimento</th>
                          <th>Situação</th>
                          <th>Ação</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map((r) => (
                          <tr key={r.id}>
                            <td>{patientName(r.patientId)}</td>
                            <td>{brl(r.amountCents || 0)}</td>
                            <td>
                              <Status value={r.status!} />
                            </td>
                            <td>
                              <button
                                className="text-button"
                                onClick={() =>
                                  window.prompt(
                                    "Confira os dados antes de preencher no serviço oficial. Copie o texto selecionado:",
                                    `Pagador: ${String(r.payerName || "Pendente")}\nCPF/CNPJ pagador: ${String(r.payerTaxId || "Pendente")}\nBeneficiário: ${String(r.beneficiaryName || "Pendente")}\nCPF beneficiário: ${String(r.beneficiaryCpf || "Pendente")}\nRecebimento: ${brl(r.amountCents || 0)}`,
                                  )
                                }
                              >
                                Copiar dados para conferência
                              </button>
                              <button
                                className="text-button"
                                onClick={() => {
                                  setSelected(r);
                                  setModal("receipt");
                                }}
                              >
                                Registrar referência <ArrowUpRight size={14} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <Empty
                    title="Tudo em seu tempo"
                    description="Referências aparecem após recebimentos elegíveis registrados e conciliados."
                  />
                )}
              </section>
            </>
          )}
          {section === "documentos" && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Documentos psicológicos</h2>
                <span className="count-label">
                  Modelos revisados · assinatura externa
                </span>
              </div>
              {rows.length ? (
                <div className="document-grid">
                  {rows.map((d) => (
                    <button
                      key={d.id}
                      className="document-card"
                      onClick={() => setSelected(d)}
                    >
                      <span className="document-card-icon">
                        <FileText size={25} />
                      </span>
                      <Status value={d.status!} />
                      <h3>{docLabel(d.type || "")}</h3>
                      <p>{patientName(d.patientId)}</p>
                      <small>
                        Versão {d.version || 1} <ArrowUpRight size={15} />
                      </small>
                    </button>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Registros com cuidado e responsabilidade"
                  description="Crie um rascunho a partir de um modelo homologado. A emissão depende da assinatura e de sua revisão."
                />
              )}
            </section>
          )}
          {section === "conteudos" && (
            <>
              <div
                className="tabs"
                role="tablist"
                aria-label="Tipos de conteúdo"
              >
                {["Todos", "Materiais", "Blog", "Newsletter"].map((t) => (
                  <button
                    key={t}
                    role="tab"
                    aria-selected={
                      tab === t || (tab === "geral" && t === "Todos")
                    }
                    className={
                      tab === t || (tab === "geral" && t === "Todos")
                        ? "active"
                        : ""
                    }
                    onClick={() => setTab(t)}
                  >
                    {t}
                  </button>
                ))}
              </div>
              <section className="panel">
                {rows.length ? (
                  <div className="document-grid">
                    {rows
                      .filter(
                        (r) =>
                          tab === "Todos" ||
                          tab === "geral" ||
                          r.entryType ===
                            {
                              Materiais: "MATERIAL",
                              Blog: "BLOG",
                              Newsletter: "NEWSLETTER",
                            }[tab],
                      )
                      .map((r) => (
                        <button
                          className="content-card"
                          key={r.id}
                          onClick={() => setSelected(r)}
                        >
                          <div className="content-art">
                            <Dandelion size={90} />
                            <span>
                              {r.entryType === "MATERIAL"
                                ? "CUIDADO QUE ACOMPANHA"
                                : "REFLEXÕES PARA A VIDA"}
                            </span>
                          </div>
                          <div>
                            <Status value={r.status!} />
                            <h3>{r.title}</h3>
                            <small>
                              {r.visibility === "PUBLIC"
                                ? "Conteúdo público"
                                : "Compartilhamento protegido"}{" "}
                              <ArrowUpRight size={15} />
                            </small>
                          </div>
                        </button>
                      ))}
                  </div>
                ) : (
                  <Empty
                    title="Uma biblioteca de possibilidades"
                    description="Organize materiais e publique reflexões com revisão de conteúdo e consentimento de comunicação."
                  >
                    {newButton("content", "Criar conteúdo")}
                  </Empty>
                )}
              </section>
            </>
          )}
          {section === "configuracoes" && (
            <>
              {!demo && (
                <CompletionSettings
                  orgId={orgId}
                  organization={organization}
                  professionals={professionals}
                  patients={patients}
                  onDone={() => {
                    setNotice("Atualização registrada com sucesso.");
                    void load();
                  }}
                />
              )}
              <div className="tabs" role="tablist" aria-label="Configurações">
                {[
                  ["geral", "Políticas"],
                  ["equipe", "Equipe"],
                  ["agenda", "Horários e preços"],
                  ["integracoes", "Integrações"],
                  ["modelos", "Modelos"],
                  ["homologacoes", "Homologações"],
                ].map(([slug, label]) => (
                  <button
                    key={slug}
                    role="tab"
                    aria-selected={tab === slug}
                    className={tab === slug ? "active" : ""}
                    onClick={() => setTab(slug)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <section className="panel settings-panel">
                {tab === "geral" ? (
                  <SettingsForm
                    initial={rows[0]}
                    version={organization?.settingsVersion || 1}
                    busy={busy}
                    onSave={(body) =>
                      mutate(
                        "/settings",
                        body,
                        "PATCH",
                        organization?.settingsVersion || 1,
                      )
                    }
                  />
                ) : tab === "agenda" ? (
                  <>
                    <div className="panel-heading">
                      <h2>Disponibilidade e valores</h2>
                      <div className="button-row">
                        {newButton("availability", "Novo horário")}
                        {newButton("price", "Novo preço")}
                      </div>
                    </div>
                    <p className="muted">
                      Horários públicos são oferecidos somente para
                      profissionais revisados e com disponibilidade cadastrada.
                      Exceções e compromissos preservam os horários ocupados.
                    </p>
                    <ProfessionalList
                      rows={professionals}
                      onReview={(p) => {
                        const evidence = window.prompt(
                          "Referência verificável da revisão do CRP:",
                        );
                        if (evidence)
                          void mutate("/professionals/" + p.id + "/review", {
                            status:
                              p.verificationStatus === "VERIFIED"
                                ? "SUSPENDED"
                                : "VERIFIED",
                            evidence,
                          });
                      }}
                    />
                  </>
                ) : tab === "equipe" ? (
                  <>
                    <div className="panel-heading">
                      <h2>Equipe do consultório</h2>
                      <div className="button-row">
                        {newButton("invite", "Convidar pessoa")}
                        {newButton("professional", "Cadastrar profissional")}
                      </div>
                    </div>
                    <ProfessionalList
                      rows={professionals}
                      onReview={(p) => {
                        const evidence = window.prompt(
                          "Referência verificável da revisão do CRP:",
                        );
                        if (evidence)
                          void mutate("/professionals/" + p.id + "/review", {
                            status:
                              p.verificationStatus === "VERIFIED"
                                ? "SUSPENDED"
                                : "VERIFIED",
                            evidence,
                          });
                      }}
                    />
                    {rows
                      .filter((r) => !String(r.userId).startsWith("worker-"))
                      .map((r) => (
                        <div className="integration-row" key={r.id}>
                          <span>
                            <strong>{String(r.roles || "Membro")}</strong>
                            <small>ID da conta: {String(r.userId)}</small>
                          </span>
                          <Status value={r.status!} />
                          {r.status === "ACTIVE" && (
                            <button
                              className="text-button danger-text"
                              disabled={busy}
                              onClick={() => {
                                const role = String(r.roles || "")
                                  .replace(/[{}]/g, "")
                                  .split(",");
                                void mutate(
                                  "/members/" + r.id,
                                  { status: "REVOKED", roles: role },
                                  "PATCH",
                                  r.version || 1,
                                );
                              }}
                            >
                              Revogar acesso
                            </button>
                          )}
                        </div>
                      ))}
                    <Notice>
                      O vínculo administrativo permite gestão. O acesso clínico
                      exige papel profissional e atribuição ao paciente.
                    </Notice>
                  </>
                ) : tab === "integracoes" ? (
                  <>
                    <div className="panel-heading">
                      <h2>Conexões do consultório</h2>
                      {newButton("integration", "Registrar conexão")}
                    </div>
                    {rows.length ? (
                      rows.map((r) => (
                        <div className="integration-row" key={r.id}>
                          <ShieldCheck size={24} />
                          <span>
                            <strong>{String(r.label)}</strong>
                            <small>{String(r.provider)}</small>
                          </span>
                          <Status value={r.status!} />
                          <button
                            className="text-button"
                            disabled={busy}
                            onClick={() =>
                              mutate("/integrations/" + r.id + "/activate", {
                                decisionCode: "HOMOLOGATED",
                                evidence:
                                  window.prompt(
                                    "Referência da evidência de homologação:",
                                  ) || "",
                              })
                            }
                          >
                            Ativar após revisão
                          </button>
                        </div>
                      ))
                    ) : (
                      <Empty
                        title="Conecte com responsabilidade"
                        description="Segredos ficam no OpenBao. Configure as referências do cofre e registre a homologação antes de ativar."
                      />
                    )}
                  </>
                ) : tab === "modelos" ? (
                  <>
                    <div className="panel-heading">
                      <h2>Modelos e versões</h2>
                      {newButton("template", "Novo modelo")}
                    </div>
                    {rows.length ? (
                      rows.map((r) => (
                        <div className="integration-row" key={r.id}>
                          <FileText size={24} />
                          <span>
                            <strong>{docLabel(String(r.docType))}</strong>
                            <small>Versão {r.version}</small>
                          </span>
                          <Status value={r.status!} />
                          <button
                            className="text-button"
                            onClick={() =>
                              mutate(
                                "/document-templates/" + r.id + "/approve",
                                {
                                  decisionCode: "APPROVED",
                                  evidence:
                                    window.prompt(
                                      "Referência da revisão clínica:",
                                    ) || "",
                                },
                              )
                            }
                          >
                            Registrar revisão
                          </button>
                        </div>
                      ))
                    ) : (
                      <Empty
                        title="Modelos validados por quem cuida"
                        description="A criação de documentos exige modelos revisados pelo responsável clínico."
                      />
                    )}
                  </>
                ) : (
                  <>
                    <div className="panel-heading">
                      <h2>Evidências de homologação</h2>
                      {newButton("approval", "Registrar aprovação")}
                    </div>
                    <Notice>
                      Aprovações exigem evidências reais e decisão humana. Este
                      registro não substitui o parecer responsável.
                    </Notice>
                    {rows.map((r) => (
                      <div className="integration-row" key={r.id}>
                        <ShieldCheck size={24} />
                        <span>
                          <strong>{String(r.gateCode)}</strong>
                          <small>{String(r.evidenceReference)}</small>
                        </span>
                        <Status value="VERIFIED" />
                      </div>
                    ))}
                  </>
                )}
              </section>
            </>
          )}
        </>
      )}
      {modal && (
        <Modal title={formTitle(modal)} onClose={() => setModal(null)}>
          <EntityForm
            kind={modal}
            patients={patients}
            professionals={professionals}
            payers={payers}
            busy={busy}
            error={error}
            onSubmit={(v) => submit(modal, v)}
            onError={setError}
          />
        </Modal>
      )}
      {selected && !modal && (
        <Modal
          title={
            section === "pacientes"
              ? selected.displayName || "Paciente"
              : section === "agenda"
                ? "Detalhes do encontro"
                : section === "financeiro"
                  ? "Detalhes da fatura"
                  : section === "conteudos"
                    ? selected.title || "Conteúdo"
                    : "Documento do cuidado"
          }
          onClose={() => setSelected(null)}
        >
          <div className="detail-content">
            {section === "agenda" && (
              <>
                <div className="detail-identity">
                  <span className="patient-avatar large">
                    {patientName(selected.patientId).slice(0, 1)}
                  </span>
                  <div>
                    <h3>{patientName(selected.patientId)}</h3>
                    <p>
                      {date(selected.startAt!)} · {time(selected.startAt!)} às{" "}
                      {time(selected.endAt!)}
                    </p>
                  </div>
                  <Status value={selected.status!} />
                </div>
                <p className="muted">
                  {selected.modality === "REMOTE"
                    ? "Atendimento online"
                    : "Atendimento presencial"}{" "}
                  · {brl(selected.priceCents || 0)}
                </p>
                <div className="button-row">
                  {selected.status === "REQUESTED" && (
                    <button
                      disabled={busy}
                      className="button"
                      onClick={() =>
                        mutate(
                          "/appointments/" + selected.id + "/actions/accept",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Aceitar solicitação
                    </button>
                  )}
                  {["SCHEDULED", "CONFIRMED"].includes(selected.status!) && (
                    <>
                      <Link
                        className="button"
                        href={"/sessao/" + selected.id + "?org=" + orgId}
                      >
                        Abrir atendimento <Video size={16} />
                      </Link>
                      <button
                        disabled={busy}
                        className="button button-outline"
                        onClick={() =>
                          mutate(
                            "/appointments/" + selected.id + "/actions/start",
                          ).then((r) => r && setSelected(r))
                        }
                      >
                        Iniciar sessão
                      </button>
                    </>
                  )}
                  {selected.status === "IN_PROGRESS" && (
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        mutate(
                          "/appointments/" + selected.id + "/actions/complete",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Concluir atendimento
                    </button>
                  )}
                </div>
                <div className="detail-secondary-actions">
                  <button
                    className="text-button"
                    disabled={busy}
                    onClick={() => {
                      const value = window.prompt(
                        "Novo início no formato AAAA-MM-DDTHH:mm (America/Sao_Paulo):",
                      );
                      if (value) {
                        try {
                          const start = localToInstant(
                            value,
                            "America/Sao_Paulo",
                          );
                          void mutate(
                            "/appointments/" +
                              selected.id +
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
                    Solicitar remarcação
                  </button>
                  <button
                    className="text-button danger-text"
                    disabled={busy}
                    onClick={() =>
                      mutate(
                        "/appointments/" + selected.id + "/actions/cancel",
                        { reasonCode: "USER_REQUESTED" },
                      ).then((r) => r && setSelected(r))
                    }
                  >
                    Cancelar atendimento
                  </button>
                </div>
              </>
            )}
            {section === "pacientes" && (
              <>
                {!demo && (
                  <PatientDetails
                    orgId={orgId}
                    patientId={selected.id}
                    onDone={() => void load()}
                  />
                )}
                {!demo && (
                  <CareWorkflowsPanel
                    orgId={orgId}
                    patientId={selected.id}
                    clinician
                  />
                )}
                <Notice>
                  Cadastro e registros pertencem a este consultório. O acesso ao
                  prontuário depende do vínculo profissional.
                </Notice>
                <div className="button-row">
                  <Link
                    className="button"
                    href={route + "/prontuario/" + selected.id}
                  >
                    <FileText size={16} />
                    Abrir registros autorizados
                  </Link>
                  <button
                    className="button button-outline"
                    onClick={() =>
                      mutate("/patients/" + selected.id + "/invite", {
                        channel: "EMAIL",
                      })
                    }
                  >
                    Enviar convite ao portal
                  </button>
                </div>
              </>
            )}
            {section === "financeiro" && (
              <>
                <Status value={selected.status!} />
                <h2>{brl(selected.amountCents || 0)}</h2>
                <p>Recebido: {brl(selected.paidCents || 0)}</p>
                <div className="button-row">
                  {selected.status === "DRAFT" && (
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        mutate(
                          "/invoices/" + selected.id + "/actions/issue",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Emitir fatura
                    </button>
                  )}
                  {["OPEN", "OVERDUE", "PARTIALLY_PAID"].includes(
                    selected.status!,
                  ) && (
                    <>
                      <button
                        className="button"
                        disabled={busy}
                        onClick={() =>
                          mutate("/invoices/" + selected.id + "/payments", {
                            method: "PIX",
                          }).then((r) => {
                            if (r && typeof r.checkoutUrl === "string")
                              window.location.assign(r.checkoutUrl);
                          })
                        }
                      >
                        Solicitar pagamento PIX
                      </button>
                      <button
                        className="button button-outline"
                        disabled={busy}
                        onClick={() => {
                          const reason = window.prompt(
                            "Justificativa do recebimento manual (mínimo 8 caracteres):",
                          );
                          if (reason)
                            void mutate(
                              "/invoices/" + selected.id + "/manual-payments",
                              {
                                amountCents:
                                  (selected.amountCents || 0) -
                                  (selected.paidCents || 0),
                                reason,
                              },
                            ).then((r) => r && setSelected(r));
                        }}
                      >
                        Registrar recebido manual
                      </button>
                    </>
                  )}
                </div>
              </>
            )}
            {section === "documentos" && (
              <>
                <Status value={selected.status!} />
                <p>{patientName(selected.patientId)}</p>
                <div className="button-row">
                  {selected.status === "DRAFT" && (
                    <>
                      <button
                        className="button button-outline"
                        disabled={busy}
                        onClick={() =>
                          mutate(
                            "/documents/" +
                              selected.id +
                              "/actions/render-draft",
                          ).then((r) => {
                            if (typeof r?.downloadUrl === "string")
                              window.location.assign(r.downloadUrl);
                          })
                        }
                      >
                        Baixar rascunho <Download size={16} />
                      </button>
                      <button
                        className="button"
                        disabled={busy}
                        onClick={() =>
                          mutate(
                            "/documents/" +
                              selected.id +
                              "/actions/request-signature",
                          ).then((r) => r && setSelected(r))
                        }
                      >
                        Preparar assinatura externa
                      </button>
                    </>
                  )}
                  {selected.status === "PENDING_SIGNATURE" && (
                    <SignedDocument
                      orgId={orgId}
                      patientId={selected.patientId!}
                      documentId={selected.id}
                      onDone={() => {
                        setSelected(null);
                        void load();
                      }}
                    />
                  )}
                  {selected.status === "ISSUED" && (
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        mutate(
                          "/documents/" + selected.id + "/actions/publish",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Disponibilizar ao paciente
                    </button>
                  )}
                </div>
                <Notice>
                  O PDF rascunho não é um documento assinado. A emissão depende
                  do arquivo assinado e da revisão documentada pelo
                  profissional.
                </Notice>
              </>
            )}
            {section === "conteudos" && (
              <>
                <Status value={selected.status!} />
                <div
                  className="rich-content"
                  dangerouslySetInnerHTML={{
                    __html: selected.bodySanitizedHtml || "",
                  }}
                />
                <div className="button-row">
                  {selected.status === "DRAFT" && (
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        mutate(
                          "/content/" + selected.id + "/actions/review",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Enviar para revisão
                    </button>
                  )}
                  {selected.status === "REVIEW" && (
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        mutate(
                          "/content/" + selected.id + "/actions/publish",
                        ).then((r) => r && setSelected(r))
                      }
                    >
                      Publicar conteúdo revisado
                    </button>
                  )}
                  {selected.status === "PUBLISHED" &&
                    selected.entryType === "MATERIAL" && (
                      <select
                        aria-label="Compartilhar com paciente"
                        defaultValue=""
                        onChange={(e) =>
                          e.target.value &&
                          mutate("/content/" + selected.id + "/shares", {
                            patientId: e.target.value,
                          })
                        }
                      >
                        <option value="">Compartilhar com paciente…</option>
                        {patients.map((p) => (
                          <option key={p.id} value={p.id}>
                            {p.displayName}
                          </option>
                        ))}
                      </select>
                    )}
                </div>
              </>
            )}
            {error && <ErrorNotice message={error} />}
          </div>
        </Modal>
      )}
    </Shell>
  );
}

function Stat({
  icon,
  label,
  value,
  hint,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  hint: string;
}) {
  return (
    <article className="stat-card">
      <div>
        <span>{label}</span>
        <i>{icon}</i>
      </div>
      <strong>{value}</strong>
      <small>{hint}</small>
    </article>
  );
}
function ReceiptIcon() {
  return <FileText size={20} strokeWidth={1.6} />;
}
function weekDates(offset: number) {
  const base = new Date();
  base.setDate(base.getDate() - ((base.getDay() + 6) % 7) + offset * 7);
  base.setHours(12, 0, 0, 0);
  return Array.from({ length: 7 }, (_, i) => {
    const day = new Date(base);
    day.setDate(base.getDate() + i);
    return day;
  });
}
function weekLabel(offset: number) {
  const dates = weekDates(offset);
  return (
    dates[0].getDate() +
    " — " +
    dates[6].getDate() +
    " de " +
    new Intl.DateTimeFormat("pt-BR", { month: "long" }).format(dates[6])
  );
}
function WeekCalendar({
  week,
  appointments,
  patientName,
  onSelect,
}: {
  week: number;
  appointments: Row[];
  patientName: (id?: string) => string;
  onSelect: (a: Row) => void;
}) {
  const days = weekDates(week),
    hours = Array.from({ length: 11 }, (_, i) => 8 + i);
  return (
    <div
      className="calendar-scroll"
      role="region"
      aria-label="Agenda semanal"
      tabIndex={0}
    >
      <div className="week-calendar">
        <div className="calendar-corner">
          <Clock size={15} />
        </div>
        {days.map((day) => (
          <div
            key={day.toISOString()}
            className={
              "calendar-day-heading " +
              (day.toDateString() === new Date().toDateString()
                ? "is-today"
                : "")
            }
          >
            <span>
              {new Intl.DateTimeFormat("pt-BR", { weekday: "short" })
                .format(day)
                .replace(".", "")
                .toUpperCase()}
            </span>
            <strong>{day.getDate()}</strong>
          </div>
        ))}
        <div className="calendar-hours">
          {hours.map((h) => (
            <span key={h}>{String(h).padStart(2, "0")}:00</span>
          ))}
        </div>
        {days.map((day) => (
          <div className="calendar-day-column" key={day.toISOString()}>
            {hours.map((h) => (
              <div className="calendar-hour" key={h} />
            ))}
            {appointments
              .filter(
                (a) =>
                  a.startAt &&
                  new Date(a.startAt).toDateString() === day.toDateString(),
              )
              .map((a) => {
                const start = new Date(a.startAt!),
                  minutes = (start.getHours() - 8) * 60 + start.getMinutes();
                return (
                  <button
                    className={
                      "calendar-event " +
                      (a.status === "SCHEDULED"
                        ? "event-sand"
                        : a.status === "REQUESTED"
                          ? "event-outline"
                          : "")
                    }
                    key={a.id}
                    style={{
                      top: minutes * 1.4,
                      height: Math.max(
                        65,
                        ((new Date(a.endAt!).getTime() - start.getTime()) /
                          60000) *
                          1.4,
                      ),
                    }}
                    onClick={() => onSelect(a)}
                  >
                    <span>
                      {time(a.startAt!)} — {time(a.endAt!)}
                    </span>
                    <strong>
                      {patientName(a.patientId).replace(/ \(fictíci[ao]\)/, "")}
                    </strong>
                    <small>
                      {a.modality === "REMOTE" ? (
                        <Video size={12} />
                      ) : (
                        <MapPin size={12} />
                      )}{" "}
                      {a.modality === "REMOTE" ? "Online" : "Presencial"}{" "}
                      {a.status === "CONFIRMED" && <Check size={12} />}
                    </small>
                  </button>
                );
              })}
          </div>
        ))}
      </div>
    </div>
  );
}
function ProfessionalList({
  rows,
  onReview,
}: {
  rows: Row[];
  onReview?: (p: Row) => void;
}) {
  return rows.length ? (
    <div>
      {rows.map((p) => (
        <div className="integration-row" key={p.id}>
          <span className="patient-avatar">
            {p.displayName?.slice(0, 2).toUpperCase()}
          </span>
          <span>
            <strong>{p.displayName}</strong>
            <small>
              {p.crpUf} · {p.crpNumber}
            </small>
          </span>
          <Status value={p.verificationStatus || "PENDING"} />
          {onReview && (
            <button className="text-button" onClick={() => onReview(p)}>
              {p.verificationStatus === "VERIFIED"
                ? "Suspender"
                : "Registrar revisão CRP"}
            </button>
          )}
        </div>
      ))}
    </div>
  ) : (
    <Empty
      title="Sua equipe começa aqui"
      description="Convide os profissionais, cadastre o CRP e registre a revisão administrativa."
    />
  );
}
function formTitle(kind: Exclude<ModalKind, null>) {
  return {
    patient: "Um novo vínculo de cuidado",
    appointment: "Um novo encontro",
    invoice: "Criar fatura",
    payer: "Cadastrar pagador",
    document: "Preparar documento",
    content: "Criar conteúdo",
    receipt: "Registrar recibo externo",
    professional: "Cadastrar profissional",
    availability: "Definir disponibilidade",
    price: "Cadastrar preço",
    invite: "Convidar para a equipe",
    template: "Novo modelo de documento",
    integration: "Conectar serviço",
    approval: "Registrar aprovação humana",
  }[kind];
}
function docLabel(value: string) {
  return (
    (
      {
        DECLARATION: "Declaração",
        CERTIFICATE: "Atestado",
        REPORT: "Relatório",
        OPINION: "Parecer",
        PSYCHOLOGICAL_REPORT: "Laudo psicológico",
      } as Record<string, string>
    )[value] || value
  );
}
function exportCsv(rows: Row[]) {
  const cols = ["id", "dueOn", "amountCents", "paidCents", "status"];
  const csv =
    "\uFEFF" +
    [
      cols.join(";"),
      ...rows.map((r) =>
        cols
          .map(
            (k) =>
              '"' +
              String(r[k] ?? "")
                .replaceAll('"', '""')
                .replace(/^[=+@-]/, "'") +
              '"',
          )
          .join(";"),
      ),
    ].join("\r\n");
  const url = URL.createObjectURL(
    new Blob([csv], { type: "text/csv;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = "financeiro.csv";
  a.click();
  URL.revokeObjectURL(url);
}

function EntityForm({
  kind,
  patients,
  professionals,
  payers,
  busy,
  error,
  onSubmit,
  onError,
}: {
  kind: Exclude<ModalKind, null>;
  patients: Row[];
  professionals: Row[];
  payers: Row[];
  busy: boolean;
  error: string;
  onSubmit: (values: Record<string, string>) => Promise<void>;
  onError: (e: string) => void;
}) {
  const input = (
    label: string,
    name: string,
    type = "text",
    required = true,
    defaultValue?: string,
  ) => (
    <Field key={name} label={label}>
      <input
        name={name}
        type={type}
        required={required}
        defaultValue={defaultValue}
        maxLength={type === "text" ? 500 : undefined}
      />
    </Field>
  );
  const select = (
    label: string,
    name: string,
    options: { id: string; label: string }[],
    required = true,
  ) => (
    <Field key={name} label={label}>
      <select name={name} required={required} defaultValue="">
        <option value="">Selecione…</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.label}
          </option>
        ))}
      </select>
    </Field>
  );
  const patient = () =>
    select(
      "Paciente",
      "patientId",
      patients.map((p) => ({ id: p.id, label: p.displayName || p.id })),
    );
  const professional = (required = true) =>
    select(
      "Profissional",
      "professionalId",
      professionals.map((p) => ({ id: p.id, label: p.displayName || p.id })),
      required,
    );
  const type = () =>
    select(
      "Tipo de documento",
      "type",
      [
        "DECLARATION",
        "CERTIFICATE",
        "REPORT",
        "OPINION",
        "PSYCHOLOGICAL_REPORT",
      ].map((id) => ({ id, label: docLabel(id) })),
    );
  const textarea = (label: string, name: string) => (
    <Field label={label}>
      <textarea name={name} required rows={7} maxLength={50000} />
    </Field>
  );
  return (
    <form
      className="entity-form"
      onSubmit={async (e) => {
        e.preventDefault();
        try {
          onError("");
          await onSubmit(
            Object.fromEntries(
              new FormData(e.currentTarget).entries(),
            ) as Record<string, string>,
          );
        } catch (e) {
          onError(e instanceof Error ? e.message : "Revise os campos.");
        }
      }}
    >
      <div className="form-grid">
        {kind === "patient" && (
          <>
            {input("Nome completo", "fullName")}
            {input("Nome social", "socialName", "text", false)}
            {input("Email", "email", "email", false)}
            {input("Telefone com DDI (+55…)", "phone", "tel", false)}
            {input("Data de nascimento", "birthDate", "date", false)}
            {professional(false)}
          </>
        )}
        {kind === "appointment" && (
          <>
            {patient()}
            {professional()}
            {input("Início · America/Sao_Paulo", "startAt", "datetime-local")}
            {input("Fim · America/Sao_Paulo", "endAt", "datetime-local")}
            {select("Modalidade", "modality", [
              { id: "REMOTE", label: "Online" },
              { id: "IN_PERSON", label: "Presencial" },
            ])}
            {input("Valor acordado (R$)", "price", "text", true, "")}
          </>
        )}
        {kind === "payer" && (
          <>
            {input("Nome do pagador", "fullName")}
            {select("Pessoa", "type", [
              { id: "PF", label: "Pessoa física" },
              { id: "PJ", label: "Pessoa jurídica" },
            ])}
            {input("CPF/CNPJ (somente números)", "cpfCnpj", "text", false)}
            {patient()}
          </>
        )}
        {kind === "invoice" && (
          <>
            {select(
              "Pagador",
              "payerId",
              payers.map((p) => ({ id: p.id, label: p.displayName || p.id })),
            )}
            {input("Vencimento", "dueOn", "date")}
            {input("Valor (R$)", "price")}
            {input(
              "ID do atendimento concluído (opcional)",
              "appointmentId",
              "text",
              false,
            )}
          </>
        )}
        {kind === "document" && (
          <>
            {patient()}
            {type()}
            {input("Versão do modelo aprovado", "templateVersion")}
            {textarea("Conteúdo preparado pelo profissional", "content")}
          </>
        )}
        {kind === "content" && (
          <>
            {select("Tipo", "entryType", [
              { id: "MATERIAL", label: "Material" },
              { id: "BLOG", label: "Blog" },
              { id: "NEWSLETTER", label: "Newsletter" },
            ])}
            {input("Título", "title")}
            {input("Slug público (opcional)", "slug", "text", false)}
            {select("Visibilidade", "visibility", [
              { id: "PRIVATE_TEAM", label: "Somente equipe" },
              {
                id: "PATIENT_SHARED",
                label: "Compartilhamento explícito com paciente",
              },
              { id: "PATIENT_ASSIGNED", label: "Pacientes atribuídos" },
              { id: "PUBLIC", label: "Público" },
            ])}
            {textarea("Conteúdo para revisão", "bodyHtml")}
          </>
        )}
        {kind === "receipt" && (
          <>
            {input(
              "Identificador do recibo no serviço oficial",
              "officialReceiptId",
            )}
            {input("Data da emissão externa", "externalIssuedOn", "date")}
            <Notice>
              Ao registrar, você declara ter concluído a emissão no serviço
              oficial. Os dados serão conferidos localmente com o recebimento.
            </Notice>
          </>
        )}
        {kind === "professional" && (
          <>
            {input("ID do usuário membro da equipe", "userId")}
            {input("Nome profissional", "displayName")}
            {input("UF do CRP", "crpUf")}
            {input("Número do CRP", "crpNumber")}
            {select("Deseja um perfil público?", "publicProfileEnabled", [
              { id: "false", label: "Não" },
              { id: "true", label: "Sim, após revisão do registro" },
            ])}
          </>
        )}
        {kind === "availability" && (
          <>
            {professional()}
            {select(
              "Dia da semana",
              "weekday",
              [
                "Segunda",
                "Terça",
                "Quarta",
                "Quinta",
                "Sexta",
                "Sábado",
                "Domingo",
              ].map((label, i) => ({ id: String(i + 1), label })),
            )}
            {input("Início", "localStart", "time")}
            {input("Fim", "localEnd", "time")}
            {input("Vigente a partir de", "effectiveFrom", "date")}
            {input("Fuso IANA", "timezone", "text", true, "America/Sao_Paulo")}
          </>
        )}
        {kind === "price" && (
          <>
            {professional()}
            {select("Modalidade", "modality", [
              { id: "REMOTE", label: "Online" },
              { id: "IN_PERSON", label: "Presencial" },
            ])}
            {input("Valor (R$)", "price")}
            {input("Vigente a partir de", "effectiveFrom", "date")}
          </>
        )}
        {kind === "invite" && (
          <>
            {input("Email verificado da pessoa", "email", "email")}
            {select("Papel", "role", [
              { id: "PSYCHOLOGIST", label: "Psicólogo(a)" },
              { id: "SECRETARY", label: "Secretaria" },
              { id: "BILLING", label: "Financeiro" },
              { id: "ADMIN", label: "Administração" },
            ])}
          </>
        )}
        {kind === "template" && (
          <>
            {type()}
            {input("Versão do modelo", "version")}
            {textarea("Modelo para homologação clínica", "content")}
          </>
        )}
        {kind === "integration" && (
          <>
            {select(
              "Serviço",
              "provider",
              ["ASAAS", "EMAIL", "WHATSAPP", "LIVEKIT", "DOC_SIGNING"].map(
                (id) => ({ id, label: id }),
              ),
            )}
            {input("Nome da conexão", "label")}
            {input("Referência no OpenBao (conhecerse/dev/…)", "secretsPath")}
          </>
        )}
        {kind === "approval" && (
          <>
            {select(
              "Gate de aprovação",
              "gateCode",
              ["LEGAL", "CLINICAL", "FISCAL", "PSP", "VIDEO", "BUSINESS"].map(
                (id) => ({ id: "GATE-" + id + "-01", label: id }),
              ),
            )}
            {input("Referência verificável da evidência", "evidenceReference")}
          </>
        )}
      </div>
      {error && <ErrorNotice message={error} />}
      <div className="form-footer">
        <span>
          <ShieldCheck size={14} /> Acesso protegido e auditado
        </span>
        <SaveButton busy={busy}>
          {kind === "receipt" ? "Registrar declaração" : "Salvar registro"}
        </SaveButton>
      </div>
    </form>
  );
}

function SettingsForm({
  initial,
  version,
  busy,
  onSave,
}: {
  initial?: Row;
  version: number;
  busy: boolean;
  onSave: (body: unknown) => Promise<unknown>;
}) {
  return (
    <form
      className="entity-form"
      onSubmit={(e) => {
        e.preventDefault();
        const data = new FormData(e.currentTarget);
        void onSave({
          minNoticeHours: Number(data.get("minNoticeHours")),
          cancelChargePercent: Number(data.get("cancelChargePercent")),
          defaultTimezone: String(data.get("defaultTimezone")),
          maxAppointmentsPerDay: Number(data.get("dailyLimit")),
          communicationDailyLimit: Number(data.get("communicationLimit")),
          policyVersion: String(data.get("policyVersion")),
          cancellationPolicy: String(data.get("cancellationPolicy")),
          privacyNoticeVersion: String(data.get("privacyVersion")),
          contingencyChannel: String(data.get("contingencyChannel")),
          emailEnabled: data.get("emailEnabled") === "on",
          whatsappEnabled: data.get("whatsappEnabled") === "on",
          holidays: String(data.get("holidays") || "")
            .split(/[\s,]+/)
            .filter(Boolean),
          openingHours: Array.from({ length: 7 }, (_, i) => ({
            weekday: i + 1,
            start: String(data.get("start" + (i + 1)) || ""),
            end: String(data.get("end" + (i + 1)) || ""),
          })).filter((h) => h.start && h.end),
        });
      }}
    >
      <span className="eyebrow">POLÍTICA VERSIONADA · V{version}</span>
      <h2>Combinados do consultório</h2>
      <div className="form-grid">
        <Field label="Antecedência de cancelamento (horas)">
          <input
            name="minNoticeHours"
            type="number"
            min="0"
            max="720"
            defaultValue={Number(initial?.minNoticeHours ?? 24)}
          />
        </Field>
        <Field label="Percentual de cobrança acordado">
          <input
            name="cancelChargePercent"
            type="number"
            min="0"
            max="100"
            defaultValue={Number(initial?.cancelChargePercent ?? 0)}
          />
        </Field>
        <Field label="Fuso do consultório">
          <input
            name="defaultTimezone"
            defaultValue={String(
              initial?.defaultTimezone || "America/Sao_Paulo",
            )}
          />
        </Field>
      </div>
      <div className="form-grid">
        <Field label="Atendimentos por profissional por dia">
          <input
            name="dailyLimit"
            type="number"
            min={1}
            max={100}
            defaultValue={Number(initial?.maxAppointmentsPerDay || 12)}
            required
          />
        </Field>
        <Field label="Limite diário de comunicações">
          <input
            name="communicationLimit"
            type="number"
            min={1}
            max={10000}
            defaultValue={Number(initial?.communicationDailyLimit || 1000)}
            required
          />
        </Field>
        <Field label="Versão dos combinados">
          <input
            name="policyVersion"
            defaultValue={String(initial?.policyVersion || "v1")}
            required
          />
        </Field>
        <Field label="Versão do aviso de privacidade">
          <input
            name="privacyVersion"
            defaultValue={String(initial?.privacyNoticeVersion || "v1")}
            required
          />
        </Field>
        <Field label="Canal combinado para contingência">
          <input
            name="contingencyChannel"
            defaultValue={String(initial?.contingencyChannel || "")}
            required
            maxLength={500}
          />
        </Field>
      </div>
      <Field label="Política de cancelamento">
        <textarea
          name="cancellationPolicy"
          defaultValue={String(initial?.cancellationPolicy || "")}
          maxLength={4000}
        />
      </Field>
      <label className="checkbox-label">
        <input
          name="emailEnabled"
          type="checkbox"
          defaultChecked={initial?.emailEnabled !== false}
        />
        Email habilitado
      </label>
      <label className="checkbox-label">
        <input
          name="whatsappEnabled"
          type="checkbox"
          defaultChecked={initial?.whatsappEnabled === true}
        />
        WhatsApp com consentimento
      </label>
      <Field label="Feriados e dias fechados (AAAA-MM-DD, separados por vírgula)">
        <textarea
          name="holidays"
          defaultValue={
            Array.isArray(initial?.holidays) ? initial.holidays.join(", ") : ""
          }
        />
      </Field>
      <fieldset>
        <legend>Horários de funcionamento da clínica</legend>
        <p>
          Preencha abertura e fechamento para cada dia de atendimento. Exceções
          individuais continuam na disponibilidade do profissional.
        </p>
        {[
          "Segunda",
          "Terça",
          "Quarta",
          "Quinta",
          "Sexta",
          "Sábado",
          "Domingo",
        ].map((label, i) => {
          const h = Array.isArray(initial?.openingHours)
            ? initial.openingHours.find(
                (h: { weekday: number }) => h.weekday === i + 1,
              )
            : null;
          return (
            <div className="form-grid" key={label}>
              <Field label={label + " · abertura"}>
                <input
                  name={"start" + (i + 1)}
                  type="time"
                  defaultValue={h?.start || ""}
                />
              </Field>
              <Field label={label + " · fechamento"}>
                <input
                  name={"end" + (i + 1)}
                  type="time"
                  defaultValue={h?.end || ""}
                />
              </Field>
            </div>
          );
        })}
      </fieldset>
      <Notice>
        Percentual acima de zero exige política aceita e aprovação de negócio. A
        política de cada atendimento é preservada como snapshot.
      </Notice>
      <SaveButton busy={busy}>Versionar política</SaveButton>
    </form>
  );
}

function ClinicalPanel({
  orgId,
  patients,
  initialPatient,
  demo,
  onNotice,
}: {
  orgId: string;
  patients: Row[];
  initialPatient?: string;
  demo: boolean;
  onNotice: (s: string) => void;
}) {
  const [patient, setPatient] = useState(
      initialPatient || patients[0]?.id || "",
    ),
    [notes, setNotes] = useState<Row[]>([]),
    [selected, setSelected] = useState<Row | null>(null),
    [text, setText] = useState(""),
    [plan, setPlan] = useState(""),
    [dirty, setDirty] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const base = "/v1/orgs/" + orgId;
  const load = useCallback(async () => {
    if (!patient || demo) return;
    try {
      const result = await request<Page>(
        base + "/patients/" + patient + "/clinical-notes",
      );
      setNotes(result.items);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Acesso indisponível.");
    }
  }, [patient, demo, base]);
  useEffect(() => {
    setSelected(null);
    setText("");
    setPlan("");
    void load();
  }, [load]);
  async function save(sign = false) {
    if (demo) {
      onNotice(
        "Registros clínicos estão vazios nesta demonstração. Use uma conta autorizada para registrar dados.",
      );
      return;
    }
    if (!patient) return;
    setBusy(true);
    setError("");
    try {
      let record = selected;
      if (!record) {
        record = await request<Row>(
          base + "/patients/" + patient + "/clinical-notes",
          {
            method: "POST",
            body: JSON.stringify({
              patientId: patient,
              occurredAt: new Date().toISOString(),
              narrative: text,
              plan,
            }),
          },
        );
      } else if (dirty) {
        record = await request<Row>(base + "/clinical-notes/" + record.id, {
          method: "PATCH",
          headers: { "If-Match": '"v' + record.version + '"' },
          body: JSON.stringify({ narrative: text, plan }),
        });
      }
      if (sign) {
        record = await request<Row>(
          base + "/clinical-notes/" + record.id + "/sign",
          {
            method: "POST",
            headers: { "If-Match": '"v' + record.version + '"' },
          },
        );
      }
      setSelected(record);
      setDirty(false);
      onNotice(
        sign
          ? "Evolução assinada. Alterações posteriores exigem adendo."
          : "Rascunho salvo com segurança.",
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Não foi possível salvar.");
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    if (
      !dirty ||
      busy ||
      selected?.status === "SIGNED" ||
      !selected ||
      !text.trim()
    )
      return;
    const timer = setTimeout(() => {
      void save(false);
    }, 15000);
    return () => clearTimeout(timer);
  });
  const locked = selected?.status === "SIGNED";
  return (
    <>
      <Notice>
        Somente profissionais verificados e atribuídos ao paciente acessam os
        registros clínicos. Rascunhos são privados do autor.
      </Notice>
      <div className="clinical-layout">
        <aside className="panel clinical-list">
          <Field label="Paciente em cuidado">
            <select
              value={patient}
              onChange={(e) => setPatient(e.target.value)}
            >
              <option value="">Selecione um paciente</option>
              {patients.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName}
                </option>
              ))}
            </select>
          </Field>
          <button
            className="button button-outline"
            onClick={() => {
              setSelected(null);
              setText("");
              setPlan("");
              setDirty(false);
            }}
          >
            <Plus size={16} />
            Nova evolução
          </button>
          {notes.map((n) => (
            <button
              className={
                "note-list-item " + (selected?.id === n.id ? "selected" : "")
              }
              key={n.id}
              onClick={() => {
                setSelected(n);
                setText(n.narrative || "");
                setPlan(n.plan || "");
                setDirty(false);
              }}
            >
              <FileText size={18} />
              <span>
                {date(n.occurredAt!)}
                <small>
                  {n.noteType === "ADDENDUM" ? "Adendo" : "Evolução"}
                </small>
              </span>
              <Status value={n.status!} />
            </button>
          ))}
        </aside>
        <section className="panel clinical-editor">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">REGISTRO CLÍNICO PROTEGIDO</span>
              <h2>{locked ? "Evolução assinada" : "Seu espaço de registro"}</h2>
            </div>
            <Status value={selected?.status || "DRAFT"} />
          </div>
          {error && <ErrorNotice message={error} />}
          <Field label="Evolução do atendimento">
            <textarea
              rows={12}
              value={text}
              readOnly={locked}
              onChange={(e) => {
                setText(e.target.value);
                setDirty(true);
              }}
              onBlur={() => {
                if (dirty && selected) void save();
              }}
              placeholder="Registre as informações necessárias ao cuidado, com respeito à privacidade."
            />
          </Field>
          <Field label="Plano de acompanhamento">
            <textarea
              rows={3}
              value={plan}
              readOnly={locked}
              onChange={(e) => {
                setPlan(e.target.value);
                setDirty(true);
              }}
            />
          </Field>
          <div className="form-footer">
            <span>
              <ShieldCheck size={14} />
              {locked
                ? "Conteúdo original preservado"
                : "Criptografia por registro · autosave em 15s"}
            </span>
            <div className="button-row">
              {locked ? (
                <button
                  className="button button-outline"
                  disabled={busy}
                  onClick={async () => {
                    const narrative = window.prompt(
                      "Conteúdo inicial do adendo:",
                    );
                    if (!narrative) return;
                    try {
                      const result = await request<Row>(
                        base + "/clinical-notes/" + selected.id + "/addenda",
                        {
                          method: "POST",
                          body: JSON.stringify({
                            patientId: patient,
                            occurredAt: new Date().toISOString(),
                            narrative,
                            noteType: "ADDENDUM",
                          }),
                        },
                      );
                      setSelected(result);
                      setText(result.narrative || "");
                      setPlan("");
                      await load();
                    } catch (e) {
                      setError(String(e));
                    }
                  }}
                >
                  Criar adendo
                </button>
              ) : (
                <>
                  <button
                    className="button button-outline"
                    disabled={busy || !text.trim() || !patient}
                    onClick={() => save()}
                  >
                    Salvar rascunho
                  </button>
                  <button
                    className="button"
                    disabled={busy || !text.trim() || !patient}
                    onClick={() => save(true)}
                  >
                    Assinar evolução <Check size={16} />
                  </button>
                </>
              )}
            </div>
          </div>
        </section>
      </div>
      {patient && !demo && <ClinicalFiles orgId={orgId} patientId={patient} />}
    </>
  );
}
function SignedDocument({
  orgId,
  patientId,
  documentId,
  onDone,
}: {
  orgId: string;
  patientId: string;
  documentId: string;
  onDone: () => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <form
      className="entity-form"
      onSubmit={async (e) => {
        e.preventDefault();
        const form = new FormData(e.currentTarget),
          file = form.get("file") as File;
        setBusy(true);
        try {
          if (file.size > 25 * 1024 * 1024)
            throw new Error("O limite é 25 MB.");
          const bytes = await file.arrayBuffer();
          const digest = checksum(bytes);
          const upload = await request<{ id: string; uploadUrl: string }>(
            "/v1/orgs/" + orgId + "/files/uploads",
            {
              method: "POST",
              body: JSON.stringify({
                filename: file.name,
                contentType: "application/pdf",
                sizeBytes: file.size,
                sha256: digest,
                classification: "SENSITIVE_CLINICAL",
                patientId,
              }),
            },
          );
          const put = await fetch(upload.uploadUrl, {
            method: "PUT",
            headers: { "Content-Type": "application/pdf" },
            body: bytes,
          });
          if (!put.ok) throw new Error("Falha no armazenamento do arquivo.");
          const validation = await request<{ status: string }>(
            "/v1/orgs/" + orgId + "/files/" + upload.id + "/complete",
            { method: "POST" },
          );
          if (validation.status !== "READY")
            throw new Error(
              "O arquivo foi rejeitado na validação e permanece em quarentena.",
            );
          await request(
            "/v1/orgs/" +
              orgId +
              "/documents/" +
              documentId +
              "/actions/submit-signature",
            {
              method: "POST",
              body: JSON.stringify({
                signedFileId: upload.id,
                signatureMethod: String(form.get("method")),
                verificationStatus: String(form.get("verificationStatus")),
                verificationEvidence: String(form.get("evidence")),
              }),
            },
          );
          onDone();
        } catch (e) {
          setError(e instanceof Error ? e.message : "Falha no arquivo.");
        } finally {
          setBusy(false);
        }
      }}
    >
      <Field label="PDF assinado externamente">
        <input name="file" type="file" accept="application/pdf" required />
      </Field>
      <Field label="Método da assinatura externa">
        <select name="method">
          <option value="GOV_BR_EXTERNAL">Gov.br</option>
          <option value="ICP_BRASIL_EXTERNAL">ICP-Brasil</option>
          <option value="PHYSICAL_SIGNED_DIGITIZED">
            Assinatura física digitalizada, conforme revisão
          </option>
        </select>
      </Field>
      <Field label="Verificação da assinatura">
        <select name="verificationStatus">
          <option value="MANUAL_REVIEWED">Revisão humana com evidência</option>
          <option value="AUTOMATIC_VALIDATED">
            PAdES com cadeia e revogação homologadas
          </option>
        </select>
      </Field>
      <Field label="Evidência da verificação humana da assinatura">
        <textarea name="evidence" minLength={8} required rows={3} />
      </Field>
      {error && <ErrorNotice message={error} />}
      <SaveButton busy={busy}>Registrar arquivo revisado</SaveButton>
    </form>
  );
}
