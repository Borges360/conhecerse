# Validação da implementação — 14/10/2026

> **Registro histórico:** este documento descreve a verificação local e o estado conhecido em 14/10/2026; a afirmação abaixo de que não havia push/deploy era correta naquele corte. O laboratório recebeu deploy posterior: Jenkins #24, commit `8661385`, tag `24-8661385`, com evidência em [dev-lab-2026-10-17-release-24.json](evidencias/dev-lab-2026-10-17-release-24.json). Para o estado corrente, consulte [IMPLEMENTACAO_PENDENCIAS.md](../docs/IMPLEMENTACAO_PENDENCIAS.md). Produção continua sem aprovação.

**Complementação:** [implementação das lacunas](../docs/IMPLEMENTACAO_PENDENCIAS.md). A seção abaixo preserva a execução anterior (97 testes/122 operações). A execução complementar tem contrato ampliado e evidência própria em `evidencias/complementacao-local.json`. Credenciais dos demais provedores serão cadastradas depois pelo usuário; isso não abre gates de produção.

**Estado: implementação local e infraestrutura preparadas; produção não liberada.** A evidência local reproduzível está em `evidencias/validacao-local.json`. Os cenários e limites estão em `../docs/RASTREABILIDADE.md`; o aceite normativo permanece em `../qa/ACEITE.md`.

## Execuções locais

Resultado final: **97 testes Java, 21 unitários web, seis cenários de navegador e duas jornadas OIDC reais aprovados**. Mutação agregada: 336/435 (77,24%); o gate também passou individualmente:

| Componente crítico | Cobertura de linhas | Mutação |
| --- | --- | --- |
| Agenda | 91,21% | 72,12% |
| Financeiro | 98,18% | 76,43% |
| Clínico | 100,00% | 93,18% |
| Autorização | 97,14% | 100,00% |

- Backend Java 21/Spring Boot 4.1.1 e PostgreSQL 17.11 real, migrações Flyway V001..V008, runtime separado sem ownership/BYPASSRLS. A suíte inclui API, concorrência, RLS, autorização clínica, revogação, pagamentos, consentimentos, relatórios, recorrência e testes de domínio independentes do controller.
- Frontend: TypeScript strict, build de produção Next.js, 21 testes unitários e seis cenários Playwright desktop/mobile. Cobrem navegação principal, semana/mês, foco/Escape, modo sintético somente leitura, cabeçalhos e axe sem violações críticas/graves nas páginas examinadas.
- Duas jornadas adicionais com Keycloak 26.8.1 real: Authorization Code/PKCE, TOTP, cookie opaco HttpOnly, onboarding; profissional revisado → disponibilidade/preço → paciente → agenda → evolução assinada → pagamento manual → conteúdo revisado/publicado, com negação CSRF. Nenhum PSP ou pagamento externo é acionado nessa jornada.
- OpenAPI/SDK: 122 operações/63 schemas sincronizados; contratos de eventos versionados. Helm lint/render e OpenTofu fmt/validate executados. Manifesto do laboratório: 11 objetos, cinco Deployments, 3840MiB.
- SCA local das dependências e secret scan sem HIGH/CRITICAL no último relatório registrado. Bases remotas Java, IAM e AV verificadas separadamente. O runtime web remove npm/yarn; o scan das imagens finais continua obrigatório no Jenkins após o build.

Contagens finais, cobertura, resultado de mutação e hashes são coletados dos relatórios reais pelo script `scripts/collect-evidence.py`, sem copiar logs, credenciais, tokens, storageState ou PII. Se um gate falhar, o JSON registra a falha; não se reduz o limiar para liberar o build. Cobertura exigida: 85% por classe crítica; mutação: 70%.

## Infraestrutura preparada

O repositório do laboratório `C:\Users\felip\Desenvolvimento\jenkies` recebeu somente arquivos locais da aplicação e alterações nas áreas previstas no QUICKSTART. GitHub configurado para `Borges360/conhecerse`, branch `main`; Jenkins `conhecerse-dev` usa BuildKit rootless, testes, SCA/SBOM, verificação de capacidade, migração separada e rollout. OpenTofu/Helm fornecem a base declarativa de produção, com entradas obrigatórias de rede, DNS, certificados e segredos.

Não foram executados push, apply remoto, primeiro build Jenkins ou deploy. O QUICKSTART reserva publicação a uma mensagem explícita do operador. `playbooks/ESTADO.md` já modificado pelo operador foi preservado. O Docker Engine não estava disponível para construir as imagens localmente; validação declarativa não comprova que os contêineres finais subiram.

## Pendências obrigatórias antes do uso com dados reais

| Gate | Evidência necessária | Responsável |
| --- | --- | --- |
| Plataforma | Build das quatro imagens, SCA/SBOM finais, assinatura, probes/rollout/OIDC no cluster, SMTP e segredos restritos/renováveis | Operação e segurança |
| KMS/arquivos | OpenBao com autenticação de workload, rotação/recovery; S3 privado, TTL/CORS, ClamAV/EICAR, falhas de upload e download 60s | Operação e segurança |
| Asaas | Conta sandbox, criação/timeout/reconciliação, dez callbacks HTTP, refund/chargeback e nenhuma duplicidade | Financeiro e integração |
| LiveKit | Dois nós, Redis HA/TLS, TURN, E2EE, Chrome/Firefox/Edge/Safari/celular, reconnect/revogação/contingência | Operação e responsável clínico |
| Comunicação | Remetente Resend, WhatsApp oficial/templates/consentimento, entrega/falha/bounce e limites de campanha | Operação e responsável por comunicação |
| Clínico/fiscal/jurídico | Modelos/documentos/assinaturas, revisão CRP, protocolo remoto, menores, retenção, aviso de privacidade, DPA/RIPD/ROPA e Receita Saúde PF/PJ | Profissional, jurídico/DPO e fiscal |
| Segurança e acessibilidade | DAST/ASVS, pentest independente, DLP integral, screen reader e avaliação com participantes reais | Segurança e UX |
| Capacidade/continuidade | 100req/s por 60min/50 tenants, soak24h, SLOs, restore/PITR com objetos+KMS e RPO/RTO, tabletop | Operação e segurança |

Os adaptadores estão implementados, mas suas credenciais, infraestrutura e aprovações não são substituídas por simulações. O sistema bloqueia funções dependentes de gates não aprovados. A demonstração identifica dados sintéticos e não grava na API. Não há gravação de atendimento, IA clínica ou integração automática gov.br.

Consulte `../docs/OPERACAO_IMPLEMENTACAO.md` para executar o ambiente e preparar a publicação conforme o laboratório. O relatório anterior de validação da especificação foi preservado em `VALIDACAO_ESPECIFICACAO_ORIGINAL.md`.
