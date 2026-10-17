# Conhecer-se · Paola Gularte

Plataforma de gestão de consultório e portal do paciente, implementada com Java 21/Spring Modulith, Next.js/TypeScript, Keycloak, PostgreSQL 17 e OpenBao. Identidade visual derivada da prancha fornecida por Paola Gularte.

O código está implementado e possui testes locais; **o aceite global de produção ainda não está concluído**. Consulte [validação](ops/VALIDACAO_IMPLEMENTACAO.md) e [rastreabilidade](docs/RASTREABILIDADE.md). Integrações clínicas, fiscais, PSP e vídeo permanecem bloqueadas enquanto suas aprovações e credenciais não forem configuradas. A demonstração visual usa dados sintéticos isolados.

## Executar localmente

Requisitos: Java 21, Node 24.21.0, pnpm 10.28.2, Python 3 e Docker Engine ativo. Em PowerShell, na raiz:

```powershell
corepack enable
corepack prepare pnpm@10.28.2 --activate
pnpm install --frozen-lockfile
.\scripts\init-local.ps1 -Start
python scripts/bootstrap-local.py
.\scripts\run-local.ps1 migrate
```

Em três terminais separados:

```powershell
.\scripts\run-local.ps1 api
.\scripts\run-local.ps1 worker
.\scripts\run-local.ps1 web
```

Abra `http://localhost:3000`. A demonstração está em `/demonstracao/dashboard`; dados reais usam `/app` e `/portal`. Configure SMTP no realm Keycloak para verificação de email e recuperação de conta. Faça o primeiro login, configure TOTP, crie a organização, cadastre/revise o CRP, a disponibilidade e os preços. O diretório público exige opt-in explícito do profissional.

Os segredos são gerados em `infra/secrets/local.env`, ignorado pelo Git. O bootstrap guarda recuperação do OpenBao local em arquivo privado; nunca utilize esse material ou o usuário raiz do S3 local em produção. Renove o token local com `scripts/run-local.ps1 renew-kms`. Veja [operação](docs/OPERACAO_IMPLEMENTACAO.md).

## Verificações

```powershell
python scripts/generate_contracts.py --check
pnpm typecheck
pnpm test
pnpm build
pnpm test:e2e
cd backend
.\mvnw.cmd verify
```

As integrações Java usam PostgreSQL 17.11 real via Testcontainers. Um PostgreSQL isolado já existente pode ser informado por `TEST_PG_ADMIN_URL`, `TEST_PG_ADMIN_USER`, `TEST_PG_ADMIN_PASSWORD`; os nomes `conhecerse_test` e seus três papéis são exclusivos do teste. Nunca aponte esse harness para um banco com dados reais. [OIDC real e fluxo vertical](qa/automation/README.md) têm execução adicional própria.

`backend/mvnw.cmd -Prelease-gates verify` exige cobertura de linhas >=85% nas classes críticas e mutation score >=70%. Esses gates não são dispensados pelo sucesso da suíte de desenvolvimento.

Execute `python scripts/check-critical-quality.py` após o Maven para verificar ambos os limiares **em cada componente**. Os pipelines já executam essa checagem. `python scripts/generate_events.py --check` valida o contrato de eventos. A comparação Word/Markdown encontrou os mesmos 93 IDs de requisitos na baseline.

## Infraestrutura e Jenkins

O laboratório foi preparado conforme `C:\Users\felip\Desenvolvimento\jenkies\docs\QUICKSTART-APP.md`: namespace `dev`, cinco Deployments, PostgreSQL no `db01`, BuildKit rootless, GitHub `Borges360/conhecerse`, branch `main`, job `conhecerse-dev`. Limites da aplicação: **3840 MiB**; o pipeline mede capacidade disponível antes das migrações, sem aumentar a VM.

```powershell
python scripts/check-infra.py
python scripts/prepare_lab.py --lab C:\Users\felip\Desenvolvimento\jenkies
```

Esses comandos somente preparam/validam arquivos. O código já foi publicado e o primeiro build/deploy de laboratório foi concluído pelo Jenkins #24; o estado atual e os limites estão no [runbook](docs/OPERACAO_IMPLEMENTACAO.md) e na [evidência da release 24](ops/evidencias/dev-lab-2026-10-17-release-24.json). A aplicação permanece somente no laboratório, com `releaseApproved=false`; publicação de novas releases e qualquer apply de infraestrutura seguem o fluxo operacional explícito, nunca `kubectl apply` direto dos manifestos.

Produção: `infra/opentofu` provisiona RDS privado Multi-AZ, buckets privados/KMS, backup com object lock, Redis HA e dois nós LiveKit com ALB/TLS e TURN/TLS. O chart `infra/helm/conhecerse` inclui probes, políticas de rede, PDB, métricas e autenticação do OpenBao por workload. Valores de rede, DNS, certificados, AMI, segredos e evidências são entradas obrigatórias; nenhum apply foi executado.

## Organização do código

| Caminho | Responsabilidade |
| --- | --- |
| `apps/web` | BFF OIDC, site público, clínica, portal, sala e design system |
| `backend/src/main/java/com/psicocare` | Módulos, autorização, transações, workers e adaptadores |
| `backend/src/main/resources/db/migration` | Migrações Flyway imutáveis, papéis, RLS e constraints |
| `contracts/generate_openapi.py` | Fonte OpenAPI, SDK e schemas sincronizados; complementação em ADR-017 |
| `scripts/generate_contracts.py` | SDK TypeScript, validação Zod e metadados da API |
| `infra`, `pipelines` | Docker, Compose, Helm, OpenTofu e Jenkins |
| `qa`, `ops`, `docs` | Aceite, evidências, identidade, operação e decisões |

A ordem normativa continua: `DECISOES.md`, `SPECIFICACAO_TECNICA.md`, contratos, schema inicial e `qa/ACEITE.md`. [ADR-016](docs/adr/ADR-016-implementacao.md) registra as adaptações de implementação; o Word e a especificação consolidada foram analisados junto às fontes canônicas. [Inventário](docs/fontes-analisadas.json).

Os campos e fluxos pendentes do relatório foram acrescentados por [ADR-017](docs/adr/ADR-017-completar-lacunas.md). Consulte [implementação e configuração](docs/IMPLEMENTACAO_PENDENCIAS.md) para protocolo remoto, portabilidade, responsáveis, acesso emergencial, mensalidade SaaS, assinaturas e callbacks de mensagens. As migrações novas são expansivas; credenciais e homologações reais continuam obrigatórias.
