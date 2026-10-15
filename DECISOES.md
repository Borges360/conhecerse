# ADRs, invariantes e política de alteração

## ADR-001 — Monólito modular e separação do worker
**Contexto:** necessidade de velocidade de implementação com qualidade, sem operação distribuída complexa. **Decisão:** Spring Modulith em Java 21 LTS, único repositório e domínio transacional PostgreSQL. Web Next.js + BFF; `api` e `worker` usam mesma base de código, processos diferentes. **Alternativa rejeitada:** microserviços por domínio; alto custo de deploy, transações distribuídas e observabilidade. **Reversibilidade:** publicar eventos versionados permite extração posterior. **Gate:** teste ArchUnit proíbe referências ao pacote `internal` de outro módulo.

## ADR-002 — Tenancy, RLS e acesso clínico
**Contexto:** clínica e profissionais não podem cruzar dados. **Decisão:** tenant por organização com `tenant_id`, FKs compostas, RLS `ENABLE/FORCE`, contexto de sessão por transação e verificação de `care_assignments` nos serviços e RLS do domínio clínico. **Risco:** SQL sem contexto ou conexões reaproveitadas; usar `SET LOCAL` só dentro de transação, nunca `SET` de sessão. **Gate:** cliente banco em produção não é table owner nem BYPASSRLS; testes forçam troca de organização, papel e paciente.

## ADR-003 — Criptografia clínica e separação de dados
**Decisão:** narrativas, materiais de teste, documentos clínicos e anexos protegidos em aplicação (envelope encryption com chave de tenant por KMS), sem índices textuais e sem plaintext em observabilidade. Rotação de KEK reenvolve DEK, sem mudar payload; rotação de DEK exige recriptografia auditada. **Trade-off:** pesquisa clínica full-text não disponível; privacidade prevalece.

## ADR-004 — Agenda transacional
**Decisão:** bloqueio de overlap `EXCLUDE USING gist (tenant_id WITH =, professional_id WITH =, busy_range WITH &&)` para estados ativos, `busy_range` inclui buffers. Remarcação atômica. **Trade-off:** agenda não permite overbooking nem duplicidades de trabalho; override requer fluxo específico não incluído.

## ADR-005 — Cobranças operadas por PSP
**Decisão:** clínicas recebem por conta própria vinculada ao PSP. Plataforma não intermedeia saldo ou cartões. Webhook at-least-once, inbox dedup e consulta de estado canônico, ledger compensatório. **Risco:** onboarding de subcontas e limites do PSP mudam; homologação obrigatória. **Gate:** testes sandbox de cobrança e webhook.

## ADR-006 — Política de sessão clínica
**Decisão:** LiveKit self-host em dois nós com rede pública, Redis HA e TURN/TLS (sem Egress), E2EE obrigatória, sem gravação/transcrição, tokens efêmeros; sem armazenar chave de sala; sem mídia no backend. Vídeo não é prova de comparecimento. **Gate:** teste real multi navegador, TURN, reconexão, bloqueio pós-revogação, medição no Brasil.

## ADR-007 — Prontuário e documentos
**Decisão:** notas assinadas imutáveis; retificação via adendo, materiais de teste segregados; acesso por paciente por fluxo verificável com revisão conforme normas. Templates não produzem conclusões clínicas automáticas. **Gate:** responsável técnico valida termos, formatos e processos de disponibilização e retenção.

## ADR-008 — Receita Saúde
**Decisão:** assistente de dados e navegação oficial, emissão manual, reconciliação assistida. Não realizar OAuth gov.br, scraping, automação de browser, coleta de credenciais nem alegar emissão do recibo no sistema. **Mudança:** nova integração exige evidência de API autorizada + aderência fiscal + ADR.

## ADR-009 — Calendário local e horário de verão
**Decisão:** UTC na persistência, timezone IANA por tenant/sessão, recorrência expressa em hora local, sem ignorar DST; horário inválido recebe erro; ambíguo requer offset. **Gate:** testes em regiões com transições, mesmo que `America/Sao_Paulo` não tenha DST atualmente.

## ADR-010 — Sem IA clínica na baseline
**Decisão:** nenhum processamento por LLM de dados clínicos, nenhum diagnóstico, resumo de sessão ou recomendação terapêutica. **Mudança:** requer novo RIPD, base legal, opção clara, validação clínica, critérios de acurácia, atribuição de responsabilidade e consentimentos quando aplicáveis.

## Invariantes globais executáveis
- INV-01: nenhum recurso de paciente/agenda/financeiro/clínico tem `tenant_id` ausente ou incompatível com o request.
- INV-02: `care_assignment` ativo é requisito para profissional ler/escrever caso clínico.
- INV-03: não há duas sessões ativas com intervalos ocupados sobrepostos para o mesmo profissional.
- INV-04: um pedido de remarcação `ACCEPTED` aponta para exatamente uma nova sessão e a anterior está cancelada.
- INV-05: documento emitido e nota assinada nunca alteram conteúdo original; emenda gera versão/registro distinto.
- INV-06: fatura `PAID` só se conciliação e saldo pagável >= valor da fatura, com métodos manuais explicitamente classificados.
- INV-07: cada evento PSP tem efeito financeiro no máximo uma vez.
- INV-08: um evento de notificação pode gerar no máximo um envio por destinatário/canal/template, exceto reenvio manual auditado.
- INV-09: nenhuma chave de sessão, narrativa clínica, CPF ou token aparece em logs e telemetria.
- INV-10: encerramento de conta não elimina registros sob obrigação de guarda ou legal hold.
- INV-11: papel administrativo não concede acesso a conteúdo clínico sem credencial profissional e vínculo de cuidado.
- INV-12: dados fiscais de recibo apenas acompanham referência oficial, sem aparência de recibo emitido pela plataforma.

## Política de mudança
Qualquer alteração de estado, regra financeira, permissão, retenção, fornecedor, schema ou contrato HTTP exige issue com impacto em `INV`, ADR numerada, migração reversível ou plano expand/contract, atualização OpenAPI/schema/testes, aprovação responsável técnico quando envolver conteúdo clínico, aprovação DPO/jurídico se envolver dados e mudança de política de acesso. É proibida decisão implícita gerada por IA em tema jurídico-clínico.
