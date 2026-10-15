# Plano de testes e matriz de aceite — obrigatório para go-live

## Convenção
Cada cenário tem **Given/When/Then**, evidência automática ou manual, responsável e rastreabilidade. Pipeline executa testes unitários, de contrato, integração PostgreSQL real com Testcontainers, E2E Playwright, DAST, SAST, SCA e smoke de terceiros em sandbox. Fixtures são sintéticas e devem conter canários fictícios para detecção de vazamento.

| ID | Given | When | Then |
|---|---|---|---|
| T-001 | dois tenants e usuário de A | consulta recurso de B alterando UUID/rota | 404; nenhum metadado de B; evento de segurança |
| T-002 | dono de A não psicólogo | consulta nota clínica de paciente de A | 404; nenhuma chave de decrypt é acionada |
| T-003 | psicólogo vinculado P1, não P2 | lê evolução de P2 | 404 e logs sem narrativa |
| T-004 | duas solicitações concorrentes do mesmo slot | ambas criam agendamento | exatamente uma 201 e outra 409; sem duplicata no BD |
| T-005 | remarcação para horário ocupado | aceita pedido | 409; sessão antiga permanece ativa; pedido PENDING |
| T-006 | paciente confirma duas vezes com mesmo link | executa ação | status CONFIRMED uma vez, um evento/outbox, UX sucesso idempotente |
| T-007 | sessão concluída | tentar cancelar/iniciar | 409 sem mutação |
| T-008 | paciente chega 20 min após hora | entrar na sala | permite acesso se dentro janela; não transforma falta automaticamente |
| T-009 | participante tenta entrar sala de outro atendimento | recebe token | 403/404; nenhum token gerado |
| T-010 | sala já encerrada | token antigo/reingresso após revogação | novo token negado; token/participante revogado conforme cenário homologado |
| T-011 | LiveKit indisponível | entrar sessão | mensagem de contingência, agenda íntegra, falha observável |
| T-012 | profissional prepara rascunho | assina | hash e data persistidos, conteúdo posterior imutável |
| T-013 | nota assinada | faz PUT de narrativa | 409; adendo permitido em novo registro |
| T-014 | usuário baixa documento liberado | link expira em 60s | HTTP 403; arquivo não cacheado |
| T-015 | anexo MIME não permitido/executável | upload | 422, quarantine/delete, sem URL de download |
| T-016 | 10 webhooks iguais de pagamento confirmado | processar em paralelo | uma movimentação contábil, uma notificação de pago |
| T-017 | callback de criado chega após recebido | processar | nunca regride PAID para OPEN |
| T-018 | PSP indisponível ao cobrar | criar fatura | fatura aberta e tentativa pendente, não PAID; reprocessa sem duplicidade |
| T-019 | reembolso pós-quitação | callback REFUNDED | estado compensatório e ledger negativo, sem apagar pagamento histórico |
| T-020 | pago por responsável diferente do paciente | gerar recibo assistido | mostrar pagador/beneficiário separados, nunca preencher automaticamente Receita Saúde |
| T-021 | recibo externo não registrado | visualizar financeiro | status `RECEIPT_PENDING`, não exibir recibo oficial inexistente |
| T-022 | usuário marca `EXTERNAL_ISSUED` | sem identificador e data coerentes | 422, requer revisão |
| T-023 | marketing opt-out | campanha newsletter | nenhum envio, transacional permitido conforme política |
| T-024 | relatório diário enviado duas vezes por scheduler | execução concorrente | relatório único `(tenant,local_date,type)` |
| T-025 | auditoria de logger com canários CPF e notas | requests e exceções | zero vazamento em logs/traces/metrics |
| T-026 | paciente pede eliminação de prontuário sob obrigação | DSR | workflow de revisão sem exclusão indevida |
| T-027 | guardião com poder de acesso questionado | solicita prontuário | trava para análise jurídico-clínica, sem exposição automática |
| T-028 | armazenamento de anexos falha após BD salvo | worker reconcilia | referência não publicada sem objeto validado, retry/DLQ |
| T-029 | restore backup isolado | PITR para timestamp selecionado | BD consistente; RPO/RTO demonstrados |
| T-030 | pessoa com teclado/screen reader | percorre cadastro, agenda, sala e checkout | foco/labels/contraste e fluxo WCAG 2.2 AA |
| T-031 | 100 req/s agenda | teste 60min | métricas SLO atingidas, 0 double booking |
| T-032 | carga de 75k sessões/semana extrapolada | soak 24h staging | DB, jobs e P95 dentro de orçamento |
| T-033 | login força bruteforce | 6 falhas em 15min | bloquear/limitar conforme política, sem enumeração de conta |
| T-034 | arquivo clínica armazenado | tentar URL pública do bucket | 403; somente via app autorizado URL efêmera |
| T-035 | secret canary e token em request | inspeção telemetry | redigido; scanner CI aprovado |
| T-036 | nota clínica em material compartilhado de marketing | tentativa de template com campo sensível | rejeitar template e bloquear envio |
| T-037 | série semanal atravessa DST | gerar ocorrências | preserva hora local válida; inválidos sinalizados, sem deslocamento silencioso |
| T-038 | `Idempotency-Key` igual e corpo diferente | POST financeiro | 409 `IDEMPOTENCY_MISMATCH` |
| T-039 | modificar sem `If-Match` | PATCH agendamento | 428 |
| T-040 | paciente de clínica A faz login também em B | alterna portal | vinculações independentes, sem fusão clínica implícita |

## Testes por módulo e rastreabilidade

### Complementação das lacunas (ADR-017)

| Cenário | Verificação adicional |
| --- | --- |
| T-041 | Acesso emergencial nega autoaprovação e leitura antes de duas revisões independentes; permite somente notas assinadas, bloqueia escrita, expiração e revogação. |
| T-042 | Cadastro ampliado e perfil público com revisão; contato de emergência não aparece para administração sem vínculo clínico. |
| T-043 | Protocolo remoto versionado e cifrado; aprovação exige gate e profissional vinculado; decisão terminal não é repetida. |
| T-044 | Feriado e limite diário negam agenda; políticas e canais têm histórico de versões. |
| T-045 | Leitura pública mantém somente conteúdo/dia/contagem, sem pessoa, IP ou perfil clínico. |
| T-046 | Liberação clínica individual ao responsável exige revisões jurídica e clínica independentes; prontuário direto continua negado. |
| T-047 | Acesso/portabilidade não podem ser marcados como concluídos antes da entrega autenticada; titular não prepara o próprio pacote. |
| T-048 | Mensalidade SaaS é independente; ausência de aprovação/configuração não cria assinatura ou cobrança clínica. |
| T-049 | Assinatura PAdES sintética: bytes, digest, certificado, cadeia/CRL e homologação; adulteração e formatos não suportados nunca viram validação automática. Homologar também certificados ICP-Brasil reais. |
| T-050 | Callback alterado ou Svix expirado é negado. Homologar entrega/bounce, callbacks duplicados/atrasados, supressão, limite diário e DLQ com fornecedores reais. |

O ciclo real de arquivos é reproduzível por `scripts/check-local-providers.py` e `qa/automation/storage-flow.cjs`; indisponibilidade não conta como sucesso. A confirmação de portabilidade exige revisão do conteúdo, download autenticado, expiração e auditoria com S3/AV reais.

- `ORG-*`: memberships, política snapshot, lista pública por opt-in, ativação/arquivamento.
- `PAT-*`: convite token uso único, menores/guardião, DSR, limite por paciente.
- `SCH-*`: property-based state machine, horários locais, overlap GiST, race conditions.
- `VID-*`: navegador Chrome/Firefox/Edge/Safari suportado em versão atual, celular; TURN fallback; reconnect; E2EE; dispositivo sem câmera.
- `CLI-*`: adendo, checksum, RLS/ABAC, retenção, teste material segregado.
- `BIL-*`: sandbox PSP, partial payment, late/duplicate/out-of-order webhook, retry e chargeback.
- `GOV-*`: fluxo 100% assistido, dados copiar, nenhuma credencial gov.br no app.
- `DOC-*`: templates homologados, hash, anexos, link autorizado.
- `COM-*`: consentimento marketing, opt-out, template validado, retries, relatórios.
- `CNT-*`: XSS HTML, asset access, newsletter unsubscribe, SEO com dados públicos.

## Critérios para liberar produção (todos, sem exceção)
1. Build de todos os serviços reproduzível no CI; contrato OpenAPI valida e passa contract tests.
2. Teste RLS e ABAC em múltiplos tenants 100% verde e revisado por segurança independente.
3. Não há vulnerabilidade Crítica/Alta aberta nos componentes expostos nem segredo hardcoded.
4. Sandbox PSP cobre criação, conciliação, chargeback, refund e duplicidade.
5. Vídeo possui teste operacional de E2EE, reconnect, TURN e controles de acesso homologados.
6. Modelos clínicos, política de guarda, termos e fluxos fiscais aprovados por especialistas responsáveis.
7. Restore e tabletop incident response documentados; suporte e operação treinados.
8. Usabilidade e acessibilidade aprovadas por participantes reais de consultório e pacientes.
9. Coleta de analytics não persiste dados clínicos; auditoria de DLP verde.
10. Todas as funcionalidades declaradas no escopo estão ativas sem dados/serviços fictícios.

## Evidência de cada execução
O CI deve publicar relatório JUnit, cobertura, mutation testing, DAST, SCA, SBOM, checklist manual assinado, relatório de carga, logs do restore, relatório jurídico-clínico e hash do commit/release. Cada teste crítico vincula ID de requisito e cenário ao PR e ao release.
