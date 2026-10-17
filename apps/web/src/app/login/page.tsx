import Link from "next/link";
import { redirect } from "next/navigation";
import { ArrowUpRight, LockKeyhole, ArrowLeft } from "lucide-react";
import { Brand, Dandelion } from "@/components/brand";
export default async function Login({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; next?: string }>;
}) {
  const params = await searchParams;
  if (!params.error) {
    const next = params.next || "/app";
    redirect("/api/auth/login?next=" + encodeURIComponent(next));
  }
  return (
    <main className="login-page" id="conteudo">
      <div className="login-art">
        <div className="login-quote">
          <Dandelion size={90} />
          <p>
            Entre o que dói
            <br />e o que faz sentido,
            <br />
            <em>existe um caminho.</em>
          </p>
          <small>PAOLA GULARTE · PSICÓLOGA CLÍNICA</small>
        </div>
      </div>
      <div className="login-content">
        <Brand />
        <div>
          <span className="eyebrow">UM ESPAÇO SEU</span>
          <h1>
            Que bom ter
            <br />
            você <em>por aqui.</em>
          </h1>
          <p>Acesse seu consultório ou portal do paciente com segurança.</p>
          {params.error && (
            <div className="notice notice-error" role="alert">
              {params.error === "configuracao"
                ? "O acesso seguro ainda está em configuração. Tente novamente após a habilitação do serviço de identidade."
                : "Não foi possível validar o acesso. Inicie uma nova autenticação."}
            </div>
          )}
          <a
            className="button login-button"
            href={
              "/api/auth/login?next=" +
              encodeURIComponent(params.next || "/app")
            }
          >
            Entrar no meu espaço <ArrowUpRight size={18} />
          </a>
          <div className="login-security">
            <LockKeyhole size={17} />
            <span>
              Autenticação protegida e verificação em duas etapas para a equipe.
            </span>
          </div>
          <Link className="text-link" href="/demonstracao">
            Explorar uma demonstração <ArrowUpRight size={15} />
          </Link>
        </div>
        <Link className="text-link" href="/">
          <ArrowLeft size={16} /> Voltar ao início
        </Link>
      </div>
    </main>
  );
}
