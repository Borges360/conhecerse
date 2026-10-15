import { Brand } from "@/components/brand";
export default function Privacy() {
  return (
    <div className="public-site">
      <header className="public-header">
        <Brand />
      </header>
      <main className="privacy-page" id="conteudo">
        <span className="eyebrow">PRIVACIDADE</span>
        <h1>
          Seu cuidado merece
          <br />
          <em>um espaço protegido.</em>
        </h1>
        <p>
          Dados de atendimento pertencem ao contexto de cada consultório. O
          acesso é restrito aos profissionais autorizados e vinculados ao seu
          cuidado. Registros clínicos não são disponibilizados automaticamente
          no portal.
        </p>
        <h2>Seus direitos</h2>
        <p>
          Você pode abrir uma solicitação de acesso, correção, portabilidade,
          revisão ou eliminação no portal. Cada pedido passa por avaliação,
          incluindo obrigações de guarda e direitos de terceiros.
        </p>
        <h2>Comunicações</h2>
        <p>
          Preferências de marketing são opcionais e podem ser alteradas.
          Mensagens transacionais de atendimento são tratadas separadamente.
          Conteúdo clínico não é enviado em notificações.
        </p>
        <h2>Transparência do ambiente</h2>
        <p>
          Esta instalação de desenvolvimento não está liberada para uso com
          dados reais de saúde. Os avisos finais, bases legais, responsáveis
          pelo tratamento e canal do encarregado dependem de aprovação e
          identificação pelo consultório antes da operação.
        </p>
      </main>
    </div>
  );
}
