# Relatório de recursos pendentes ou parcialmente implementados

> Relatório de corte preservado. A implementação posterior das lacunas de código está descrita em [IMPLEMENTACAO_PENDENCIAS.md](IMPLEMENTACAO_PENDENCIAS.md). Homologações externas e condições de produção descritas abaixo permanecem pendentes até evidência real.

> **Atualização de estado em 17/10/2026:** o laboratório já recebeu o primeiro deploy (Jenkins #17, commit `e25fdbe`, tag GitOps `17-e25fdbe`). A seção 5 abaixo descreve o corte histórico de 14/10/2026; para o estado atual do laboratório, use [IMPLEMENTACAO_PENDENCIAS.md](IMPLEMENTACAO_PENDENCIAS.md) e a [evidência do release 17](../ops/evidencias/dev-lab-2026-10-17-release-17.json). Essa atualização não significa aceite de produção.

**Data de corte:** 14/10/2026  
**Escopo:** baseline PSC-TECH-002 v2.0.0 e seu estado documentado no repositório.  
**Objetivo:** distinguir lacunas funcionais de código, integrações ainda não homologadas e condições operacionais de liberação.

## 1. Resumo executivo

A plataforma tem implementação local substancial e cobre os principais domínios da especificação. Isso ainda **não equivale à implementação integral aceita para uso com dados reais**: a própria validação registra `releaseApproved=false`, nenhum deploy remoto realizado e funções condicionadas a gates fechados. A evidência local registra 97 testes Java, 21 testes unitários web, seis cenários de navegador e duas jornadas OIDC com Keycloak real; a jornada vertical não chama o Asaas nem comprova integrações externas. [Validação da implementação](../ops/VALIDACAO_IMPLEMENTACAO.md#L1) · [evidência local](../ops/evidencias/validacao-local.json)

Na matriz de 40 cenários de aceite, 21 estão exercitados localmente (L), 12 têm evidência parcial (P) e sete dependem de execução externa (E). A legenda e os limites de cada cenário estão em [RASTREABILIDADE.md](RASTREABILIDADE.md#L24). Os itens abaixo consolidam esses cenários com lacunas funcionais adicionais identificadas ao comparar a especificação com o contrato da API.

**Conclusão:** não há evidência de que todos os domínios estejam prontos para produção. Há requisitos parcialmente representados no contrato/código, fluxos específicos sem jornada operacional demonstrada, integrações sem teste real e decisões de domínio ainda sem aprovação. A norma exige uma entrega completa antes do lançamento e proíbe substituir requisitos obrigatórios por mocks. [Especificação, escopo e convenções](../SPECIFICACAO_TECNICA.md#L8) · [critérios globais de conclusão](../SPECIFICACAO_TECNICA.md#L335)

## 2. Lacunas funcionais identificadas

| Prioridade | Recurso / requisito | Estado observado | O que falta para concluir |
|---|---|---|---|
| Alta | **Configuração da organização e perfil público — ORG-001..004** | Parcial. O contrato cobre nome, slug, tipo da pessoa jurídica e fuso; as configurações expõem aviso mínimo, taxa de cancelamento, canais e fuso. O profissional possui nome, CRP e opt-in, e há disponibilidade/preços. | Completar os dados e fluxos exigidos para a clínica (identificação fiscal do responsável, endereço, horários/feriados e políticas/canais versionados) e os atributos do perfil público (idiomas, especialidades autodeclaradas/revisadas e tipos de atendimento). A especificação também prevê limites por dia; eles não aparecem no `SettingsPatch` do contrato. Confirmar quais regras de feriado e horários já são atendidas por exceções de disponibilidade e quais ainda precisam de configuração própria. [ORG e onboarding](../SPECIFICACAO_TECNICA.md#L118) · [contratos de organização, profissional e configurações](../contracts/generate_openapi.py#L23) · [requisitos ORG](../SPECIFICACAO_TECNICA.md#L126) |
| Alta | **Cadastro de paciente — PAT-001** | Parcial. Nome, nome social, CPF, email, telefone, nascimento e preferência de comunicação estão modelados. | O requisito inclui endereço opcional e contato de emergência quando indicado; esses campos não aparecem no schema de criação/alteração do paciente nem no modelo `patients` da migração base. Acrescentar apenas se a finalidade clínica exigir e com proteção/minimização adequadas. [PAT-001](../SPECIFICACAO_TECNICA.md#L132) · [schema PatientCreate/Patch](../contracts/generate_openapi.py#L29) · [tabela de pacientes](../backend/src/main/resources/db/migration/V001__baseline.sql#L54) |
| Alta | **Acesso de responsável legal — PAT-005** | Parcial e conservador. A API permite vínculo de responsável, guarda evidência e restringe escopos iniciais a agenda e financeiro; a revisão manual existe. | Não há escopo de leitura clínica no contrato de vínculo. É necessário definir e implementar, se aprovado, a liberação clínica individualizada após revisão legal-clínica. Até lá, acesso automático a prontuário deve continuar bloqueado. [PAT-005](../SPECIFICACAO_TECNICA.md#L137) · [GuardianCreate](../contracts/generate_openapi.py#L198) · [T-027](RASTREABILIDADE.md#L56) |
| Alta | **Fluxo de acesso/portabilidade de prontuário — PAT-004 / CLI-007** | Parcial. Há pedido auditado e revisão humana; a matriz afirma que o pacote é preparado/revisado por profissional. | Demonstrar o fluxo completo de geração, revisão, entrega autenticada, expiração do link e registro de conclusão, incluindo portabilidade quando aplicável e prazo operacional. Não transformar o pedido em liberação automática de material restrito. [PAT-004](../SPECIFICACAO_TECNICA.md#L136) · [CLI-007](../SPECIFICACAO_TECNICA.md#L182) · [rastreabilidade clínica](RASTREABILIDADE.md#L13) |
| Alta | **Protocolo de atendimento remoto e risco agudo — VID-007** | Não evidenciado como fluxo de produto. A especificação exige coleta no onboarding de protocolo de interrupção/risco, orientações de privacidade e localização/contato de emergência quando clinicamente pertinente; o contrato de onboarding não expõe esses campos. | Criar o fluxo de registro/revisão profissional e definir como o conteúdo é protegido e disponibilizado durante contingência. A aprovação clínica do protocolo permanece necessária antes de habilitar teleconsulta. [VID-007](../SPECIFICACAO_TECNICA.md#L166) · [gate clínico pendente](../ops/VALIDACAO_IMPLEMENTACAO.md#L39) |
| Alta | **Assinaturas e mensalidade SaaS — BIL-009** | Parcial. Existe tabela `platform_subscriptions`, mas a inspeção do OpenAPI e dos módulos não encontrou operações/jornada de cobrança, renovação, cancelamento ou reconciliação de assinatura da plataforma. | Definir e implementar a jornada comercial da assinatura SaaS separada do financeiro da clínica, se ela fizer parte do produto a entregar. Preço e política de cancelamento ainda dependem do gate de negócio; não misturar esses valores com pagamentos dos pacientes. [BIL-009 e GATE-BUSINESS-01](../SPECIFICACAO_TECNICA.md#L194) · [tabela declarada](../backend/src/main/resources/db/migration/V001__baseline.sql#L496) |
| Alta | **Acesso emergencial interno / break-glass — SEC-015** | Não implementado ou não demonstrado. A especificação exige dupla aprovação, justificativa e expiração. O contrato não expõe operação de break-glass; a matriz atual não contém cenário de aceite específico para esse fluxo. | Implementar o workflow de solicitação, aprovações independentes, prazo curto, escopo mínimo, auditoria e revogação; adicionar cenário positivo e negativo. Até aceite, manter negação por padrão e sem bypass administrativo. [regra de suporte](../SPECIFICACAO_TECNICA.md#L113) · [SEC-015](../SPECIFICACAO_TECNICA.md#L272) · [critérios de liberação QA](../qa/ACEITE.md#L61) |
| Média | **Métricas agregadas de leitura — CNT-004** | Não evidenciado. Há blog, materiais e newsletter, mas a inspeção das operações documentadas não encontrou métrica agregada de leitura. | Se essa métrica for requisito da entrega, implementá-la de forma agregada, sem perfil de saúde mental e sem cookies desnecessários; adicionar verificação de privacidade. [CNT-004](../SPECIFICACAO_TECNICA.md#L224) · [requisitos de analytics](../SPECIFICACAO_TECNICA.md#L220) |
| Média | **Validação automática de assinatura documental — DOC-002** | Parcial. O sistema suporta rascunho, upload de documento assinado e revisão manual. A matriz diz que não há afirmação de PAdES/PDF-A ou validade jurídica sem homologação. | Implementar e homologar a verificação criptográfica PAdES/ICP-Brasil onde suportada, registrar evidências de verificação e manter revisão humana nos demais casos. Aprovar os modelos antes do primeiro uso. [DOC-001..005](../SPECIFICACAO_TECNICA.md#L204) · [estado documental](RASTREABILIDADE.md#L18) |
| Média | **Entrega, status e bounce de mensagens — COM-003/004 e CNT-003** | Parcial. Os adaptadores, retries, DLQ, consentimentos e deduplicação estão implementados; entrega real, callbacks e bounce dependem das contas e templates aprovados. | Configurar Resend e WhatsApp Business oficial, homologar templates/opt-in e demonstrar estados enviados, entregues, falhos e suprimidos, bounce, limites de campanha e operação de DLQ. [COM/CNT](../SPECIFICACAO_TECNICA.md#L211) · [gate de comunicação](../ops/VALIDACAO_IMPLEMENTACAO.md#L38) |

**Evidência usada para os achados de contrato:** o contrato-fonte OpenAPI define `OrganizationCreate`, `ProfessionalCreate`, `PatientCreate/Patch`, `SettingsPatch` e `GuardianCreate` em [generate_openapi.py](../contracts/generate_openapi.py#L23). Esses schemas são mais restritos que os campos descritos em ORG/PAT e sustentam os itens marcados como lacuna; a especificação e a API devem ser reconciliadas antes de considerar esses recursos completos.

## 3. Recursos implementados, mas ainda sem homologação externa

Estes itens têm implementação local/adaptadores; o que falta é validar a integração real, completar o ciclo operacional e abrir os gates correspondentes. Não devem ser anunciados como funcionais em produção antes dessas evidências.

| Recurso | Evidência que falta | Referências |
|---|---|---|
| **Armazenamento clínico, antivírus e download** | OpenBao com autenticação de workload/rotação/recovery; S3 privado e CORS/TTL; upload real validado, EICAR/ClamAV, quarentena, limite de tamanho, falha e recuperação; link ABAC de 60s e tentativa anônima no bucket. | [CLI-005](../SPECIFICACAO_TECNICA.md#L175) · [T-014, T-015, T-028, T-034](RASTREABILIDADE.md#L43) · [gate KMS/arquivos](../ops/VALIDACAO_IMPLEMENTACAO.md#L35) |
| **Asaas: checkout, webhooks e conciliação** | Conta sandbox; criação e timeout; dez callbacks HTTP autenticados/duplicados; estado canônico, reconciliação a cada 6h, refund/chargeback, falhas e ausência de duplicidade demonstrados com o provedor. A jornada local atual usa somente pagamento manual sintético. | [BIL-004..007](../SPECIFICACAO_TECNICA.md#L185) · [T-016, T-018, T-019](RASTREABILIDADE.md#L45) · [gate Asaas](../ops/VALIDACAO_IMPLEMENTACAO.md#L36) |
| **LiveKit e teleconsulta** | Dois nós, Redis HA/TLS, TURN/TLS e mídia no cluster; E2EE comprovada entre navegadores/dispositivos; matriz Chrome/Firefox/Edge/Safari/celular; reconexão, revogação/expulsão, token antigo, tentativa entre salas e contingência de sinalização/TURN. | [VID-001..007](../SPECIFICACAO_TECNICA.md#L166) · [T-008..011](RASTREABILIDADE.md#L37) · [gate LiveKit](../ops/VALIDACAO_IMPLEMENTACAO.md#L37) |
| **Receita Saúde assistida** | Homologar elegibilidade fiscal PF/PJ, jornadas e comprovantes com especialista fiscal; testar consistência e retificação/cancelamento usando referência oficial real. A plataforma registra a declaração do profissional, não verifica o recibo no governo. | [GOV-001..005](../SPECIFICACAO_TECNICA.md#L197) · [T-022](RASTREABILIDADE.md#L51) · [gate fiscal](../ops/VALIDACAO_IMPLEMENTACAO.md#L39) |
| **Templates e retenção clínica/documental** | Aprovação do psicólogo responsável e jurídico para modelos, protocolo remoto, acesso de menores/responsáveis, privacidade, DPA/RIPD/ROPA e retenção. O prazo clínico de 20 anos está explicitamente provisório até parecer. | [DOC-005](../SPECIFICACAO_TECNICA.md#L204) · [CLI-008](../SPECIFICACAO_TECNICA.md#L183) · [gates clínico/jurídico](../ops/VALIDACAO_IMPLEMENTACAO.md#L39) |

## 4. Cenários de aceite que continuam abertos

O status abaixo reproduz a matriz de rastreabilidade; **P** indica evidência parcial e **E** indica que o aceite exige ambiente/serviço externo. O resultado local não substitui a execução abaixo.

| Cenários | Estado e atividade que falta |
|---|---|
| **T-008, T-009 (P); T-010, T-011 (E)** | Entrada com mídia 20 minutos após o início e tentativa cruzada devem ser verificadas no LiveKit ativo; comprovar expulsão/reconexão e token antigo após revogação, além de simular indisponibilidade de signaling/TURN. |
| **T-014, T-015 (P); T-028, T-034 (E)** | Executar ciclo real de arquivo autorizado, EICAR e quarentena ClamAV, queda/recuperação de storage/DLQ, expiração do download e negação de acesso anônimo ao bucket. |
| **T-016, T-018, T-019 (P)** | Repetir callbacks autenticados do Asaas sandbox, criar e reconciliar cobrança com timeout/indisponibilidade, e validar refund/chargeback sem regressão ou movimento duplicado. |
| **T-022 (P)** | Homologar fiscalmente PF/PJ e fluxo real de comprovante, inclusive os critérios de recibo externo. |
| **T-025, T-035 (P)** | Completar DLP de logs, traces, métricas e imagem final com canários; verificar scanner CI e que tokens/PII não vazam. |
| **T-029 (E)** | Fazer restore isolado de banco, objetos e KMS; registrar consistência, RPO e RTO medidos. |
| **T-030 (P)** | Completar auditoria WCAG 2.2 AA com screen reader, sala e checkout, além de avaliação com participantes reais. |
| **T-031, T-032 (E)** | Executar carga de 100 req/s durante 60 min com 50 tenants sintéticos e soak de 24 h, validando SLOs e a referência de 75 mil sessões/semana. |
| **T-033 (P)** | Além do login/TOTP já testado, executar seis falhas em 15 min e validar bloqueio/limitação, recuperação e ausência de enumeração de conta. |

Referências de estado por cenário: [T-008..019](RASTREABILIDADE.md#L37), [T-020..035](RASTREABILIDADE.md#L49) e [aceite normativo](../qa/ACEITE.md#L15). Os demais cenários estão marcados como exercitados localmente, com os limites registrados na matriz.

## 5. Liberação de ambiente e produção ainda não concluída

Não foram feitos push, primeiro build Jenkins, apply remoto ou deploy. O Docker Engine não estava disponível para construir as imagens finais localmente. Helm/OpenTofu foram validados declarativamente; isso não prova que os contêineres subiram no cluster. O artefato de evidência também registra `releaseApproved=false` e `remoteDeploymentExecuted=false`. [Validação da implementação](../ops/VALIDACAO_IMPLEMENTACAO.md#L24) · [evidência](../ops/evidencias/validacao-local.json)

Antes de usar dados reais, ainda é necessário concluir:

1. **Plataforma:** build das imagens web/API/IAM/AV, SCA/SBOM finais, assinatura e verificação de imagens por digest, probes, OIDC e rollout no cluster; SMTP e segredos restritos/renováveis.
2. **KMS e arquivos:** OpenBao, rotação/recovery, S3 privado, CORS/TTL, ClamAV/EICAR e ciclo de upload/download.
3. **Fornecedores:** Asaas sandbox; LiveKit com vídeo e TURN; Resend e WhatsApp oficial, templates e callbacks.
4. **Domínio:** aceite jurídico-clínico-fiscal para consentimentos, termos, privacidade, DPA/RIPD/ROPA, CRP, menores/responsáveis, retenção, documentos e Receita Saúde PF/PJ.
5. **Segurança e acessibilidade:** DAST/ASVS, pentest independente, DLP integral, screen reader e participantes reais.
6. **Capacidade e continuidade:** carga, soak, SLOs, restore/PITR com objetos e KMS, RPO/RTO medidos e exercício tabletop.

Além dos exercícios, os alvos NFR ainda precisam ser medidos no ambiente implantado: disponibilidade API 99,9%; leitura p95/p99 ≤400/1.200 ms; escrita ≤700/1.800 ms; agenda a 100 req/s e 50 clínicas; ACK de webhook p95 ≤2 s; notificações p95 ≤60 s; relatório diário até 07:15; RPO ≤15 min e RTO ≤4 h; WCAG 2.2 AA; logs técnicos por 30 dias e auditoria de segurança por cinco anos inicialmente (sujeito à política); conciliação em até 6 h; vídeo com sucesso de entrada ≥99% e p95 ≤15 s em rede homologada. Os valores são metas da especificação, não resultados atuais. [NFR-001..013](../SPECIFICACAO_TECNICA.md#L280)

Responsáveis e evidências detalhadas estão na tabela [Pendências obrigatórias](../ops/VALIDACAO_IMPLEMENTACAO.md#L30). Os critérios globais, sem exceção, permanecem em [qa/ACEITE.md](../qa/ACEITE.md#L61); em particular, o aceite exige todas as funções do escopo ativas sem serviços ou dados fictícios.

## 6. Itens deliberadamente fora da baseline

Os itens abaixo **não são pendências desta entrega**, porque a própria especificação os exclui e exige novo ADR/projeto de conformidade para adicioná-los: diagnóstico por IA, psicometria automatizada, prescrição, prontuário irrestritamente compartilhado entre profissionais, marketplace de leads, gravação/transcrição, automação de login gov.br, aplicativos nativos, convênio/TISS, split de pagamentos, chat clínico assíncrono e emissão fiscal automática. Receita Saúde permanece assistida e manual; a emissão oficial ocorre fora da plataforma. [Exclusões explícitas](../SPECIFICACAO_TECNICA.md#L34) · [decisão de Receita Saúde](../DECISOES.md#L25)

## 7. Referências normativas consultadas

- [SPECIFICACAO_TECNICA.md](../SPECIFICACAO_TECNICA.md) — comportamento normativo e IDs ORG/PAT/SCH/VID/CLI/BIL/GOV/DOC/COM/CNT/SEC/NFR/UX.
- [contracts/openapi.yaml](../contracts/openapi.yaml) e [contracts/generate_openapi.py](../contracts/generate_openapi.py) — operações e schemas HTTP.
- [docs/RASTREABILIDADE.md](RASTREABILIDADE.md) — estado de implementação e dos 40 cenários de aceite.
- [qa/ACEITE.md](../qa/ACEITE.md) — Given/When/Then e critérios globais de liberação.
- [ops/VALIDACAO_IMPLEMENTACAO.md](../ops/VALIDACAO_IMPLEMENTACAO.md) e [evidência local](../ops/evidencias/validacao-local.json) — resultados reproduzíveis e gates ainda fechados.
- [docs/OPERACAO_IMPLEMENTACAO.md](OPERACAO_IMPLEMENTACAO.md) — configuração de provedores, deploy, recuperação e operação.
- [docs/fontes-analisadas.json](fontes-analisadas.json) — inventário/hash das fontes de especificação analisadas.
