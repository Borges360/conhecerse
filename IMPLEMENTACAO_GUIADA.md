# Guia de execução para equipe ou harness de engenharia

Este guia transforma a especificação em tarefas executáveis. **Não autoriza uso direto em produção sem aprovação jurídica/clínica/PSP/Vídeo.** Todos os módulos MUST serão entregues no mesmo marco de lançamento.

## Monorepo de referência
```text
psico-platform/
  apps/
    web/                  # Next.js TS; app router, sessão BFF, i18n pt-BR
  backend/
    api/                  # Spring Boot app HTTP
    worker/               # Spring Boot worker
    modules/
      identity/ tenancy/ patients/ scheduling/ video/
      clinical/ billing/ receipts/ documents/
      communications/ content/ reporting/ integrations/ audit/
    db/migration/         # Flyway Vxxxx__name.sql
    contracts/            # cópia canônica dos schemas OpenAPI e event JSON schemas
  infra/
    opentofu/             # infraestrutura declarativa
    helm/                 # charts API, worker, web, IAM e vídeo
  test/
    contract/ integration/ e2e/ security/ perf/
  docs/
    adr/ risk-register/ privacy/ runbooks/
  .github/workflows/      # checks, release, security
```

## Stack e critérios de fixação
- Backend Java 21 LTS; Spring Boot estável compatível; Spring Security OAuth2 Resource Server nos fluxos diretos (quando houver) e BFF com OIDC; Spring Data JDBC ou JPA decidido uma única vez pelo responsável técnico; migrations Flyway, Hibernate `ddl-auto=validate` apenas.
- Frontend Next.js estável, React/TypeScript `strict`, Zod para validação e cliente OpenAPI gerado, React Query para cache **somente** de conteúdos não sensíveis e por escopo de organização, Playwright para E2E, Tailwind/shadcn como DS operacional.
- PostgreSQL 17+, Redis HA **obrigatório** para clustering do LiveKit autogerenciado; sem Redis para jobs core. Keycloak + OpenBao, objeto S3, LiveKit autogerenciado (dois nós de rede pública, host networking, UDP/TURN/TLS, sem Egress), Asaas, canal oficial WhatsApp e email transacional homologados.
- Controlar builds via Maven Wrapper / pnpm lockfile e imagens fixadas por digest; versões escolhidas no primeiro PR e registradas em `DEPENDENCIES.lock.md` com compatibilidade/licenças, não usar `latest`.

## Entidades, DTOs, acesso e transações
1. Gerar migrations a partir de `db/schema.sql` **sem executar schema completo novamente em cada ambiente**. Runtime SQL usa papel não proprietário e sem bypass RLS; garantir `SET LOCAL app.tenant_id`, `SET LOCAL app.actor_id` em toda transação, incluindo consultas. Conexão sem contexto deve retornar zero rows, não usar pool session-pinning implícito.
2. Escrever `TenantContextResolver` que valida pertença à organização da rota com JWT verificado via OIDC. Não aceitar tenant do corpo da requisição. `CurrentPrincipal` contém identificador global e membership; papéis são reconsultados no servidor se cache tiver expirado/revogação. Testes revogam membership durante sessões.
3. Gerar DTOs e clientes a partir de OpenAPI. Nenhuma entidade JPA/JDBC exposta por controller. Conversão `amountCents` BigInteger/long e BigDecimal apenas na borda do PSP, com escala 2 e arredondamento não silencioso.
4. Implementar serviço `UnitOfWork` que grava mutação + história + outbox na mesma transação. Jobs e integrações externas **nunca** são executados dentro de lock de transação do agendamento/fatura.
5. Adotar `ProblemExceptionHandler` com erros categorizados e redaction. Logs de app nunca recebem request body completo em endpoints clínicos.

## Ordem em PRs pequenos (um único marco final de produção)
| PR group | Entregável obrigatório | Aceite que destrava |
|---|---|---|
| 01 | Monorepo, devcontainers, DB/migrations, IAM, OpenBao, auth gateway | builds, MFA, RLS isolamento |
| 02 | Organizações, profissionais, políticas, permissões, convites | ORG/PAT e 403/404 |
| 03 | Pacientes, responsáveis, vínculo assistencial, direitos de acesso | PAT-* + SEC-001/002/003 |
| 04 | Disponibilidade, recorrência, agenda, remarcação, confirmação, histórico | SCH-* + T-004..T-007,T-037 |
| 05 | Outbox/Jobs, notificações, templates, preferências, daily report | COM-* + T-023/024 |
| 06 | Vídeo, token room-bound, E2EE, preflight e contingência | VID-* + T-008..T-011 |
| 07 | Notas clínicas, assinatura/adendo, anexos, DSR, acesso ao paciente | CLI-* + T-012..T-015,026/027 |
| 08 | Faturas, pagadores, cobranças, pagamentos, ledger e reconciliador | BIL-* + T-016..T-019 |
| 09 | Recibos externos assistidos, templates/documentos psicológicos | GOV/DOC + T-020..T-022 |
| 10 | Materiais, portal, blog, newsletter, diretório público | CNT/PORTAL + E2E |
| 11 | Segurança independente, carga, restore, pareceres, rollout ensaiado | Gate final integral |

Cada PR deve alterar OpenAPI + domínio + migration + evento + testes + docs se mudou contrato. Para UI de cada domínio criar tests de permission, loading, empty, failure, success, mobile e a11y.

## Casos E2E obrigatórios de ponta a ponta
**E2E-A — Particular online:** psicólogo cria consultório → integra pagamentos e email → define horário/preço/política → publica perfil → paciente solicita horário → clínica aceita e vincula paciente → confirmação → entrada na sala → profissional conclui sessão → assina evolução → sistema gera fatura → paciente paga via PSP → webhook confirmado/settled → recibo fica pendente de emissão oficial → profissional abre Receita Saúde, emite fora do app e registra identificador → paciente recebe documento/materiais liberados. Nenhuma informação clínica chega ao PSP ou comunicação.

**E2E-B — Remarcação e não comparecimento:** agenda semanal → paciente pede remarcação → concorrência por slot gera 409 sem perda do original → aprova alternativa → notifica ambas as partes → horário novo inicia → profissional marca falta apenas com decisão explícita → taxa conforme política previamente aceita → fatura auditável.

**E2E-C — Consultório multiusuário:** dono adiciona secretaria e psicólogo → secretaria agenda e cobra → tenta ler evolução, falha → outro psicólogo tenta ler evolução, falha → autor assina nota → paciente faz pedido formal de prontuário → responsável clínico revisa divulgação → export seguro e auditado.

**E2E-D — Incidentes e recuperação:** PSP envia evento duplicado e fora de ordem → sistema mantém saldo idempotente → vídeo cai → contingência sem perda da agenda → conexão de banco restaurada com PITR → íntegro; nenhum dado sensível em log.

## Política de testes por requisito
`testCaseId` obrigatório na descrição dos testes (ex.: `T-004`). Cada endpoint `openapi.yaml` gera pelo menos smoke test auth+validation; módulos críticos testes de propriedade de máquina de estados e concorrência; integrações possuem fake test adapter mas **produção** aponta para fornecedor homologado com health e circuit breaker. E2E devem ser executados em `staging` com dados inteiramente sintéticos. Não usar snapshots de prontuário real.

## Exemplos de migração e erros proibidos
- Banco exige `tstzrange` e `EXCLUDE`: não substituir por `if (slotDisponivel)` apenas na aplicação.
- Não emitir dupla cobrança para tentar contornar timeout; consultar PSP pelo `externalReference` antes de retry de efeito externo.
- Não reutilizar login da clínica para acessar portal do paciente; vínculos e autorização são contextuais.
- Não permitir query administrativa de clinical_notes ou storage clínico mesmo que tenha `tenant_id` igual.
- Não passar narrativa clínica como payload de evento, push, email, Kafka ou analytics.
- Não oferecer botão “Recibo emitido” se usuário ainda não inseriu identificador externo e comprovou a operação no serviço oficial.
- Não considerar `CONFIRMED` do PSP necessariamente liquidado/irrevogável; considerar bloqueio cautelar, estorno e chargeback.
- Não afirmar que documento PDF sem assinatura comprovada constitui atestado/parecer assinado.

## Perguntas bloqueadoras com defaults seguros
Se clínica não aceita termo de remarcação/cancelamento: **não** cobrar penalidade; se CRP não está revisado: não publicar agenda externa; se conta PSP não está habilitada: permitir somente gestão financeira manual, sem mostrar pagamento online como funcional; se vídeo/E2EE não foi homologado: não abrir teleconsulta pelo sistema; se documentação fiscal não foi aprovada: exibir somente assistente de acesso ao site oficial e sem reivindicação de emissão; se retenção legal não foi aprovada: bloquear exclusão clínica, manter backups e legal hold.

Esses defaults são para manter desenvolvimento seguro, **não** justificam liberar produção afirmando funcionalidades integrais. A versão final exige validação de todos os gates.
