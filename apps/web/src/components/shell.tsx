"use client";
import Link from "next/link";
import { useState } from "react";
import {
  LayoutDashboard,
  CalendarDays,
  Users,
  NotebookPen,
  Wallet,
  ReceiptText,
  Files,
  BookOpen,
  Settings,
  ChevronDown,
  Bell,
  Menu,
  X,
  ArrowUpRight,
  ShieldCheck,
  LogOut,
} from "lucide-react";
import { Brand, Dandelion } from "./brand";
const navigation = [
  { slug: "dashboard", label: "Visão geral", icon: LayoutDashboard },
  { slug: "agenda", label: "Agenda", icon: CalendarDays },
  { slug: "pacientes", label: "Pacientes", icon: Users },
  { slug: "prontuario", label: "Prontuários", icon: NotebookPen },
  { slug: "financeiro", label: "Financeiro", icon: Wallet },
  { slug: "recibos", label: "Receita Saúde", icon: ReceiptText },
  { slug: "documentos", label: "Documentos", icon: Files },
  { slug: "conteudos", label: "Conteúdos", icon: BookOpen },
];
export function Shell({
  children,
  orgId,
  section,
  demo = false,
  name = "Meu consultório",
}: {
  children: React.ReactNode;
  orgId: string;
  section: string;
  demo?: boolean;
  name?: string;
}) {
  const [open, setOpen] = useState(false),
    [notifications, setNotifications] = useState(false);
  const base = demo ? "/demonstracao" : "/app/orgs/" + orgId;
  async function logout() {
    const session = await fetch("/api/session").then((r) => r.json());
    const response = await fetch("/api/auth/logout", {
      method: "POST",
      headers: { "X-CSRF-Token": session.csrfToken },
    });
    if (response.ok) {
      const result = await response.json();
      window.location.assign(result.logoutUrl);
    }
  }
  return (
    <div className="workspace">
      <button
        className="mobile-menu icon-button"
        aria-label={open ? "Fechar menu" : "Abrir menu"}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        {open ? <X /> : <Menu />}
      </button>
      {open && (
        <button
          className="sidebar-overlay"
          aria-label="Fechar menu"
          onClick={() => setOpen(false)}
        />
      )}
      <aside className={`sidebar ${open ? "sidebar-open" : ""}`}>
        <Brand />
        <div className="practice-label">
          <span>SEU CONSULTÓRIO</span>
          <strong>{demo ? "Paola Gularte" : name}</strong>
          <small>
            <span className="online-dot" />{" "}
            {demo ? "Ambiente de demonstração" : "Espaço privado"}
          </small>
        </div>
        <nav aria-label="Navegação do consultório">
          <span className="nav-label">CUIDADO E GESTÃO</span>
          {navigation.map(({ slug, label, icon: Icon }) => (
            <Link
              key={slug}
              href={base + "/" + slug}
              onClick={() => setOpen(false)}
              className={section === slug ? "nav-link active" : "nav-link"}
              aria-current={section === slug ? "page" : undefined}
            >
              <Icon size={19} strokeWidth={1.6} />
              {label}
              {slug === "agenda" && demo && (
                <span className="nav-count">14</span>
              )}
            </Link>
          ))}
          <span className="nav-label nav-label-bottom">CONSULTÓRIO</span>
          <Link
            href={base + "/configuracoes"}
            className={
              section === "configuracoes" ? "nav-link active" : "nav-link"
            }
          >
            <Settings size={19} strokeWidth={1.6} />
            Configurações
          </Link>
        </nav>
        <div className="sidebar-note">
          <Dandelion size={48} />
          <p>
            Cuidar do seu tempo
            <br />
            também é <em>cuidar de você.</em>
          </p>
        </div>
        <Link className="sidebar-portal" href={demo ? "/login" : "/portal"}>
          <ShieldCheck size={16} />
          Portal do paciente <ArrowUpRight size={15} />
        </Link>
        <div className="sidebar-user">
          <span className="avatar">
            {demo ? "PG" : name.slice(0, 2).toUpperCase()}
          </span>
          <span>
            <strong>{demo ? "Paola Gularte" : name}</strong>
            <small>{demo ? "Visualização demonstrativa" : "Meu espaço"}</small>
          </span>
          {!demo && (
            <button
              className="icon-button"
              aria-label="Sair da conta"
              onClick={logout}
            >
              <LogOut size={16} />
            </button>
          )}
        </div>
      </aside>
      <div className="workspace-body">
        <header className="workspace-topbar">
          <span className="topbar-breadcrumb">
            Meu consultório <span>/</span>{" "}
            {navigation.find((n) => n.slug === section)?.label ||
              "Configurações"}
          </span>
          <div>
            <span className="topbar-date">
              {new Intl.DateTimeFormat("pt-BR", {
                day: "numeric",
                month: "long",
                timeZone: "America/Sao_Paulo",
              }).format(new Date())}
            </span>
            <button
              className="icon-button notification-button"
              aria-label="Ver notificações"
              aria-expanded={notifications}
              onClick={() => setNotifications(!notifications)}
            >
              <Bell size={19} />
              {demo && <i />}
            </button>
            <span className="avatar avatar-small">
              {demo ? "PG" : name.slice(0, 2).toUpperCase()}
            </span>
          </div>
          {notifications && (
            <div className="notification-popover">
              <strong>Seu espaço de avisos</strong>
              <p>
                {demo
                  ? "Este é um ambiente demonstrativo com registros fictícios."
                  : "Consulte as pendências e solicitações no painel e na agenda."}
              </p>
            </div>
          )}
        </header>
        {demo && (
          <div className="demo-banner">
            <span>DEMONSTRAÇÃO · DADOS FICTÍCIOS</span>
            <span>Explore o consultório com tranquilidade.</span>
            <Link href="/login">
              Acessar conta real <ArrowUpRight size={14} />
            </Link>
          </div>
        )}
        <main className="workspace-main" id="conteudo">
          {children}
        </main>
        <footer className="workspace-footer">
          <span>
            Conhecer-se <i /> Cuidado com sentido.
          </span>
          <span>
            <ShieldCheck size={13} /> Seu espaço privado de cuidado
          </span>
        </footer>
      </div>
    </div>
  );
}
