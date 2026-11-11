# Rastreabilidade da implementação

Atualização de engenharia de 14/10/2026: campos e workflows das lacunas estão implementados em [ISSUE-PSC-017](IMPLEMENTACAO_PENDENCIAS.md), com política em [ADR-017](adr/ADR-017-completar-lacunas.md). Evidência desta execução: `../ops/evidencias/complementacao-local.json`; os aceites externos desta matriz continuam abertos.

Os resultados do laboratório #24 (`24-8661385`) constam no [registro versionado](../ops/evidencias/dev-lab-2026-10-17-release-24.json). A [reespecificação de 10/11/2026](REESPECIFICACAO_PENDENCIAS.md) consolida as pendências de entrega, incorpora os registros locais posteriores de #25 e do ensaio de login por senha e indica o alcance de cada evidência.

Baseline: DECISOES.md → SPECIFICACAO_TECNICA.md → contracts → schema → qa/ACEITE.md. O Word e a especificação consolidada também foram lidos; hashes das fontes estão em `fontes-analisadas.json`. A identidade visual utiliza a prancha original, fontes locais, azuis, areia, monograma e dente-de-leão; ver `IDENTIDADE_VISUAL.md`.

Esta matriz registra o alcance da evidência por cenário. O aceite integral segue os [critérios de liberação](../qa/ACEITE.md#critérios-para-liberar-produção-todos-sem-exceção).

## Requisitos e componentes

| Família da especificação | Implementação principal | Evidência e limite |
| --- | --- | --- |
| ORG-001..004 | `identity/IdentityModule`, settings versionados, revisão CRP, perfil opt-in, diretório e lead público; telas de configurações/profissionais | Onboarding OIDC real; aceite do lead cria paciente/vínculo/sessão em transação. Revisão profissional é humana. |
| PAT-001..005 | `patients/PatientsModule`, criptografia dos dados cadastrais, HMAC de busca exata, assignments, guardian grants, convites e DSR; cadastro/portal | Testes de autorização, transferência/revogação, responsável em revisão, vínculo independente em duas organizações. Parecer sobre menores e titularidade é externo. |
| SCH-001..010 | `scheduling/SchedulingModule`, GiST, availability/exceptions, recurrence, transições/histórico, confirmação e remarcação; agenda semana/mês/lista | Concorrência PostgreSQL, rollback, sucesso de remarcação, terminalidade, recorrência/DST, idempotência e ETag. Carga de referência e mídia permanecem pendentes. |
| VID-001..007 | `video/VideoModule`, `RoomGateway`, sala/preflight LiveKit, E2EE antes de conectar, JWT room-bound 120s, fechamento/revogação, contingência | Negação de token antes de liberar gates. TURN, E2EE entre navegadores, reconnect, expulsão e protocolo clínico exigem homologação real. Não há gravação/IA/transcrição. |
| CLI-001..008 | `clinical/ClinicalModule`, autosave cifrado, autor do rascunho, assinatura/hash/adendo, RLS e anexos separados | API real com PostgreSQL, ciphertext vinculado a tenant/registro/revisão, imutabilidade e material restrito segregado. Pacote de acesso preparado/revisado pelo profissional; retenção depende de parecer. |
| BIL-001..010 | `billing/BillingModule`, preços vigentes, payer distinto, fatura/ledger, checkout externo, `integrations/IntegrationsModule`, inbox/outbox e reconciliação | Pagamento manual vertical, replay/ordem/refund canônicos sintéticos, intenção nunca marca PAID. Asaas sandbox e chargeback integral ainda não homologados. Assinatura SaaS não mistura recursos da clínica. |
| GOV-001..005 | `receipts/ReceiptsModule`, elegibilidade, cópia individual, site oficial, declaração e revisão local, gates fiscais | Pagador/beneficiário e RECEIPT_PENDING testados. Não armazena credenciais gov.br nem emite recibo oficial. Validação fiscal e comprovante real externos. |
| DOC-001..005 | `documents/DocumentsModule`, modelos versionados, aprovação humana, PDF de rascunho, upload/revisão/publicação/revogação e verificador PAdES/CMS | Geração de PDF e verificação com certificados sintéticos exercitadas. Aprovação de templates, cadeia/CRLs operacionais e PDF/A quando aplicável exigem aceite próprio. |
| COM-001..007 | `worker/WorkerEngine`, `communications/CommunicationGateway`, consentimento separado, notificações deduplicadas, callbacks autenticados, supressão, retry/DLQ e relatório diário | Unicidade concorrente do relatório, opt-out e autenticação de callbacks testados. Entrega Resend/WhatsApp e medição dos SLOs dependem de contas/templates reais. |
| CNT-001..004 | `content/ContentModule`, biblioteca/compartilhamento, revisão de HTML, blog, newsletter double opt-in/descadastro e contagem diária agregada | Sanitização, bloqueio de campos sensíveis, consentimentos e agregação testados. Bounce e operação de campanha exigem homologação do provedor. |
| SEC-001..015 | RLS FORCE, papéis separados, `shared/Access`, PKCE/BFF/CSRF, criptografia OpenBao, ABAC no download, acesso emergencial restrito, CSP e SCA/SBOM | Autorização e OIDC exercitados. O login por senha foi decidido no [ADR-018](adr/ADR-018-autenticacao-equipe-senha.md), com pareceres pendentes. A assinatura de imagens e o auto-auth do OpenBao estão implementados, sem execução no laboratório ou no candidato. Pentest independente e DLP integral seguem pendentes. |
| NFR-001..013 | Helm/NetworkPolicy/probes/metrics, OpenTofu Multi-AZ/PITR/S3/Redis/LiveKit, workers idempotentes e scripts de carga | Validação declarativa executada. Disponibilidade, latência sob carga, RPO/RTO, retenção de telemetria e SLOs só podem ser aceitos em infraestrutura implantada e observada. |

## Cenários de aceite

**L** indica o cenário exercitado no limite descrito, localmente ou no laboratório. **P** indica evidência parcial. **E** indica que o cenário principal depende de execução externa. Engenharia mantém as evidências técnicas. Operação, segurança, fornecedores e especialistas participam dos aceites correspondentes.

| Cenário | Estado | Evidência / próximo aceite |
| --- | --- | --- |
| T-001 | L | API com dois tenants e membership revogada retorna 404; handler emite evento técnico sem conteúdo. |
| T-002 | L | Dono sem papel clínico não lê evolução; RLS/ABAC antes de decrypt. |
| T-003 | L | Profissional sem vínculo atual e transferência não abrem notas de outro cuidado. |
| T-004 | L | Duas criações concorrentes no PostgreSQL produzem uma sessão e um conflito. |
| T-005 | L | Conflito no novo horário mantém sessão anterior e pedido PENDING. |
| T-006 | L | Token repetido mantém CONFIRMED, sem novo evento. |
| T-007 | L | Transições terminais rejeitadas no domínio e persistência. |
| T-008 | P | Janela de autorização implementada; entrada com mídia após 20min precisa LiveKit homologado. |
| T-009 | P | Negação antes de gerar token testada; tentativa cruzada com LiveKit ativo ainda necessária. |
| T-010 | E | Fechamento e negação de nova entrada implementados; comprovar expulsão/reconexão e limites do token antigo no cluster. |
| T-011 | E | Mensagem de contingência e contador implementados; simular falha de sinalização/TURN em homologação. |
| T-012 | L | Assinatura persiste hash/data; ciphertext e versão testados. |
| T-013 | L | Escrita em nota assinada rejeitada; adendo em novo registro encadeado. |
| T-014 | P | Release24 comprovou download autorizado com checksum, ticket de uso único e expiração em 60s (403). Falta confirmar explicitamente cabeçalho de não armazenamento e fluxo de portabilidade/documento completo. |
| T-015 | P | Release24 rejeitou EICAR com `MALWARE_DETECTED` e negou download; a falha fechada/recuperação do ClamAV também passou. Ainda faltam probes dos formatos/MIME não aceitos e limite máximo em execução real. |
| T-016 | P | Reconciliação canônica concorrente deduplica ledger; repetir 10 webhooks HTTP autenticados do Asaas sandbox e entrega. |
| T-017 | L | Estado canônico tardio não regride pagamento SETTLED/fatura PAID. |
| T-018 | P | Provedor desabilitado mantém fatura OPEN; checkout é intenção PENDING. Falha/reconciliação real no sandbox pendente. |
| T-019 | P | Refund canônico acrescenta movimento compensatório negativo; validar callback real e chargeback sandbox. |
| T-020 | L | Pagador financeiro e beneficiário separados; cópia assistida sem automação gov.br. |
| T-021 | L | RECEIPT_PENDING exibido sem recibo oficial inventado. |
| T-022 | P | Identificador/data/valor e gate exigidos no código; aceite fiscal e jornada homologada de comprovante pendentes. |
| T-023 | L | Consentimento confirmado/descadastro e revogação pelo portal testados; envio real do canal é externo. |
| T-024 | L | Execuções concorrentes resultam em relatório único por tenant/data/tipo. |
| T-025 | P | Logs sanitizados e canários sintéticos; auditoria DLP de toda a telemetria ainda necessária. |
| T-026 | L | Pedido de eliminação abre revisão e preserva registro clínico. |
| T-027 | L | `review_required` nega acesso e a leitura direta de evolução é bloqueada. A regressão local verifica listagem com `SCHEDULE` e remoção desse acesso; #25 registrou a leitura positiva no laboratório. Ver T-046 para liberação clínica individual. |
| T-028 | E | A falha/recuperação do ClamAV passou em release24; a interrupção do storage e reconciliação/DLQ após perda de objeto ainda não foram exercitadas. |
| T-029 | E | Infra de backup/PITR declarada; drill isolado com objetos e KMS, RPO/RTO medidos. |
| T-030 | P | Release24 carregou as telas principais dos seis perfis sem violações Axe WCAG 2.2 AA; login desktop/mobile também passou. Screen reader, sala/checkout e participantes reais permanecem pendentes. |
| T-031 | E | Harness k6 100req/s/60min preparado; executar em staging autorizado com 50 tenants. |
| T-032 | E | Soak 24h e extrapolação de 75k sessões/semana não executados. |
| T-033 | P | #24 registrou desvio de relógio de 60 s e o ensaio compensou esse valor. O registro local posterior cobre seis perfis autenticados por senha. A política está no [ADR-018](adr/ADR-018-autenticacao-equipe-senha.md). Falta sincronizar o relógio do nó e testar seis falhas em 15 min, recuperação e ausência de enumeração no candidato. |
| T-034 | L | Release24 confirmou HTTP 403 para listagem anônima do bucket, 401 para download anônimo pela aplicação, download autorizado efêmero e isolamento entre organizações (404). |
| T-035 | P | Release24 passou Trivy offline no código/SBOMs/imagens finais sem segredos bloqueantes; canários em logs/traces/métricas e DLP integral continuam pendentes. |
| T-036 | L | Campo sensível bloqueado e HTML sanitizado; nenhum texto clínico em template de mensagem. |
| T-037 | L | Horário local/DST, gap/ambiguidade, limites e expansão testados. |
| T-038 | L | Mesmo Idempotency-Key com corpo diferente retorna 409; replay preserva resultado. |
| T-039 | L | Sem If-Match retorna 428; versão antiga é rejeitada. |
| T-040 | L | Portal conserva IDs e vínculos independentes entre clínicas. |
| T-041 | L | Teste de integração cobre acesso emergencial com duas aprovações independentes, escopo, expiração e registro de revogação após expirar. Demonstrar revogação durante vigência e obter aceite de segurança e do responsável clínico. |
| T-042 | L | Cadastro ampliado, minimização de contato de emergência e revisão do perfil exercitados localmente. #24 confirmou cadastro e perfil administrativo após escrita no laboratório. |
| T-043 | L | Protocolo cifrado, autorização e revisão exercitadas localmente. #24 salvou e releu o protocolo. Homologação clínica e contingência com provedor seguem abertas. |
| T-044 | L | Teste de integração exercita feriado, limite diário e histórico de configurações. |
| T-045 | L | Teste de integração verifica contagem agregada por conteúdo/dia. O aceite de privacidade inclui auditoria da coleta no ambiente candidato. |
| T-046 | L | Teste de integração exige revisões jurídica e clínica independentes e mantém bloqueada a leitura clínica direta. Parecer e jornada de entrega ao responsável continuam pendentes. |
| T-047 | P | Teste de integração nega conclusão sem entrega e preparação pelo próprio titular. Falta demonstrar preparação PDF/JSON, revisão, download autenticado, expiração/revogação e conclusão auditada com storage/AV reais. |
| T-048 | P | Teste de integração cobre bloqueio sem configuração/aprovação. Adesão, renovação, conciliação e cancelamento dependem da conta SaaS sandbox e das decisões comerciais. |
| T-049 | P | Assinatura, cadeia e revogação exercitadas com certificados sintéticos. Falta homologar certificados ICP-Brasil, CRLs operacionais e os formatos de emissão aplicáveis. |
| T-050 | P | Testes exercitam adulteração e timestamp Svix expirado. Falta comprovar entrega, bounce, duplicidade/ordenação, supressão, limites e DLQ com fornecedores reais. |
| T-051 | L | Testes locais e [laboratório #22](../ops/evidencias/dev-lab-2026-10-17-release-22.json) registram escopo `BILLING` e preferências do responsável. #24 registrou pagamento negado sem alteração da fatura. |

## Condições de liberação

Os dez critérios globais de `qa/ACEITE.md` continuam obrigatórios. As aprovações no banco não substituem o parecer, relatório ou teste que referenciam. Fixtures sintéticas de teste nunca constituem aprovação de produção. `releaseApproved=false` no chart e os gates globais/tenant permanecem fechados por padrão.

Mudanças de contrato exigem regenerar SDK/schemas e testar compatibilidade. Eventos v1 possuem JSON Schema em `contracts/events/v1/envelope.schema.json`; major desconhecida vai para DLQ com métrica/alerta, sem despacho. O envelope é representado pelas colunas da outbox e seu payload de IDs; segredos de entrega ficam cifrados fora dele. Não editar migrações já aplicadas.
