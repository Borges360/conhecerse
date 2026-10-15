# Operação, implantação e resposta a falhas

## Perfis e controle
- Plataforma com ambientes separados `dev/staging/prod`; acesso de infraestrutura concedido por OIDC + MFA + elevação temporária; deployments automatizados GitOps e revisão de duas pessoas para produção.
- Segredos via OpenBao KV v2 + autenticação por workload; KMS externo ou transit engine com políticas não exportáveis. O OpenBao armazena segredos de fornecedores sem expor chaves a IAs e não substitui arquitetura de autorização dos dados clínicos.
- Nomes de serviços `psico-web`, `psico-api`, `psico-worker`, `psico-keycloak`, `psico-postgres`, `psico-video` (dois nós rede pública com host networking e Redis HA de coordenação), `psico-objectstore`. Namespaces separados por ambiente. Domínios `app`, `api`, `meet`, `auth`, `assets` com DNS e TLS válidos.

## Backup e recuperação
- PostgreSQL PITR com WAL contínuo, backup completo diário e retenção operacional 35 dias; retenção de prontuário não depende **apenas** de backups. Backups imutáveis em conta segregada, protegidos por chave separada.
- Object store versionado, object lock conforme política, replicação de região sujeita a acordo de processamento e requisitos de soberania.
- RPO 15m; RTO 4h alvos testados a cada trimestre. Restore inclui banco, objetos, chaves e configuração declarativa; teste de integridade referências de documentos/objetos.
- Exercício de recuperação trimestral com análise de falha; pelo menos um exercício anual de indisponibilidade total da região.

## Monitoramento e alertas
| Alerta | Threshold proposto | Severidade |
|---|---|---|
| erros 5xx HTTP | >1% em 5min | P1 |
| latência p95 API | >700ms por 15min | P2 |
| DLQ jobs | >0 de módulo clínico/financeiro por 10min | P1 |
| webhook backlog | mais antigo >15min | P2 |
| cobrança divergente | diferença >0 não explicada após 6h | P1 |
| falhas de token/sala | >3% em 10min | P1 |
| backup atrasado | >24h | P1 |
| RLS/auth denial spike | anomalia >= 5x baseline | P1 |
| report diário atrasado | >15min após hora-alvo | P2 |
| expiração segredo/certificado | <14 dias | P2 |

Coletar métricas técnicas (erros, chamadas, duração, tentativas, tamanho de fila) sem nomes de pacientes ou texto clínico. Correlation ID propagado de frontend a worker com `traceparent`, sem expor JWT.

## Runbooks
**R1 PSP instável:** abrir circuit breaker, pausar novas chamadas após salvar intenção local, não alterar estado para pago, verificar status PSP, reconciliação em lote, aviso contextual ao usuário; nunca cobrar novamente sem verificar `externalReference`/pedido anterior.

**R2 vídeo indisponível:** avisar profissional e paciente na tela; manter agenda; direcionar protocolo de contingência previamente acordado; desabilitar tokens quando serviço comprometido; registrar apenas metadados operacionais.

**R3 vazamento suspeito:** preservar evidência com acesso restrito, acionar DPO/segurança, rotacionar chaves e revogar sessões conforme necessidade, avaliar comunicação exigida à ANPD e titulares, não notificar automaticamente sem classificação, documentar prazos e medidas.

**R4 corrupção de schema ou migração:** bloquear rollout, `expand/contract` migrações, escolher rollback de serviço ou restaurar banco se juridicamente/operacionalmente necessário; não apagar migrations aplicadas nem alterar tabela history manualmente.

**R5 erro de agendamento concorrente:** examinar constraint de `busy_range`/status, manter sem compromisso ao usuário até commit, retorno 409 com alternativas; nunca inserir override pela interface administrativa.

**R6 falha de jobs:** verificar locks expirados e attempts; reprocessar evento com mesmo idempotency key; DLQ revisão humana; antes de replay de pagamentos reconciliar PSP.

## CI/CD
`lint -> unit -> architectural-modules -> contract validation -> integration Testcontainers -> security SAST+SCA+secret scan -> E2E -> load smoke -> SBOM+image signature -> deploy staging -> sandbox integration -> approval -> canary production -> postdeploy smoke`. Migrations compatíveis com rollout gradual, feature flags, processo formal de rollback. IaC com plan aprovado; N-1 suporte de contrato web API por 90 dias se lançar versões públicas posteriores.

## Política de dependências
Usar versões estáveis vigentes na implementação, *pinadas*, sem `latest` no Docker ou dependências. PR de atualização com changelog, análise CVE, teste de migração, análise de compatibilidade de Java/Spring/Keycloak/Next/LiveKit, signed artifacts. Não construir dependência em preview sem ADR.

**Vídeo: requisito de rede:** LiveKit multi-node exige Redis; em Kubernetes cada pod usa host networking em nó próprio e portas de mídia públicas com regras restritas, LB para signaling HTTPS, certificado válido em TURN/TLS, observabilidade de jitter/perda e `SIGTERM` draining. Sem serviço Egress/recording implantado. A perda de um nó ativo pode interromper salas nele hospedadas; procedimentos de reconexão e comunicação de contingência devem ser aceitos no teste. Fonte: https://docs.livekit.io/transport/self-hosting/distributed/ e https://docs.livekit.io/transport/self-hosting/kubernetes/.
