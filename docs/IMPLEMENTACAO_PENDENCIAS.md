# ISSUE-PSC-017 — Implementação das lacunas do relatório

Solicitação: implementar itens faltantes do relatório de 14/10/2026. O usuário orientou utilizar os recursos locais, MinIO e OpenBao, e informou que cadastrará credenciais dos demais provedores posteriormente. Esta issue local registra impacto em permissões, schema, contratos e INV-02/07/09/11. Decisão: [ADR-017](adr/ADR-017-completar-lacunas.md).

| Item | Implementação e uso |
| --- | --- |
| ORG-001..004 | Cadastro cifrado do responsável/endereço; perfis com idiomas, especialidades declaradas/revisadas e modalidades; políticas/canais versionados; horários, feriados e limite diário aplicados pela agenda. Configurações → Cadastro, assinatura e controle de acesso. |
| PAT-001 | Endereço opcional, nascimento editável e contato de emergência cifrado com indicação clínica. Detalhes do paciente. Administração recebe cadastro mínimo; contato clínico exige vínculo. |
| PAT-005 | Vínculo básico de responsável, revisão jurídica e clínica independentes, expiração/revogação e liberação somente de pacote revisado. Responsável escolhe o paciente no portal. |
| PAT-004 / CLI-007 | Fila com prazo operacional, preparação PDF/JSON, conferência, liberação humana, ticket autenticado de 60s, conclusão após download e revogação. Detalhes do paciente → Acesso e portabilidade; paciente → portal. |
| VID-007 | Protocolo cifrado/versionado, revisão do profissional, bloqueio de vídeo sem última versão aprovada, combinados de contingência disponíveis na sala e contato clínico protegido. |
| BIL-009 | Planos no cofre da plataforma, adesão com termos, cobrança mensal recorrente Asaas, conciliação periódica/manual, cancelamento confirmado e isolamento do financeiro clínico. |
| SEC-015 | Solicitação, duas pessoas independentes aprovando, escopo de notas assinadas, MFA recente, até 15min, auditoria e revogação. Sem bypass administrativo de escrita ou material de teste. |
| CNT-004 | Contagem diária por conteúdo após exposição de cinco segundos; nenhum cookie ou identificador de visitante. Configurações → Leituras agregadas. |
| DOC-002 | Verificador PAdES/CMS, certificado vinculado, cadeia/CRLs, hash e evidência persistida; revisão manual para formatos não suportados; emissão automática exige homologação explícita. |
| COM-003/004 / CNT-003 | Callbacks Resend/WhatsApp autenticados, estados sem regressão, deduplicação, bounce/reclamação com supressão, limite diário e reprocessamento de falhas/DLQ. |
| KMS/S3/AV | MinIO privado, OpenBao transit, ClamAV e bootstrap local existentes; persistência de rejeição em quarentena, trava de validação concorrente e exercícios reais adicionados. |

## Configurar os provedores posteriormente

Não colocar segredos em Git, prompts ou relatórios. Use referências KV permitidas pelo `Crypto.secret` e cadastre conexões na interface.

- Email/Resend: `provider=RESEND`, `apiKey`, `from` validado e `webhookSecret=whsec_...`.
- WhatsApp: token oficial, `phoneNumberId`, `approvedTemplates`, `appSecret` e `verifyToken` com pelo menos 32 caracteres. Callback: `https://HOST/v1/webhooks/communications/{webhookIdentifier}`; a verificação GET de Meta é servida na mesma URL. O Ingress encaminha bytes/headers diretamente à API.
- SaaS: `PLATFORM_BILLING_SECRETS_PATH=conhecerse/{ambiente}/platform-billing`. KV com `accountPurpose=PLATFORM_SAAS`, `environment`, `apiKey` da conta da plataforma e `plans` contendo `code`, `amountCents`, `termsVersion`, condições revisadas e `approved=true` somente após aprovação real. Nenhum preço é semeado. A adesão exige `GATE-BUSINESS-01`; consultar criação incerta no provedor antes de qualquer intervenção.
- Assinatura: montar arquivos confiáveis em `ICP_BRASIL_TRUST_ANCHORS` e `ICP_BRASIL_CRLS`, atualizar CRLs por processo operacional validado, e habilitar `SIGNATURE_VALIDATION_APPROVED=true` somente após homologação. Registrar `GATE-SIGNATURE-01` no tenant. Não ativar download de CRLs/OCSP a partir de URLs de certificados não confiáveis.
- Comunicação: ativação exige `GATE-COMMUNICATION-01`. Protocolo remoto exige `GATE-CLINICAL-01`; sessão exige também `GATE-VIDEO-01`. Responsáveis exigem gates clínico/jurídico e revisões independentes. Acesso emergencial exige `GATE-SECURITY-01` e aprovadores independentes.

## Verificação reproduzível

`backend/mvnw.cmd -Prelease-gates verify`, `python scripts/check-critical-quality.py`, geração de contratos, typecheck, testes web, build e Playwright. Cenários T-041..T-048 e `SignatureAndCallbackTest` exercitam PostgreSQL real e criptografia com certificados sintéticos; não homologam ICP-Brasil/Asaas.

A execução complementar aprovou 108 testes Java, 23 testes web, seis cenários Playwright e uma jornada das telas novas autenticada por Keycloak real. O contrato tem 159 operações/79 schemas. Cobertura e mutação dos quatro componentes críticos passaram os limiares individuais; consulte `../ops/evidencias/complementacao-local.json` para hashes e percentuais. A jornada também verificou ausência de violações axe críticas/graves nos painéis examinados, sem substituir avaliação humana WCAG. DLP dos logs técnicos indicados e SCA de 147 dependências Maven passaram; imagens/traces/métricas continuam fora dessa evidência. O coletor rejeita uma análise de SCA sem pacotes.

Para dependências reais locais:

```powershell
.\scripts\init-local.ps1 -Start
python scripts/bootstrap-local.py
python scripts/check-local-providers.py
# API/BFF/OIDC locais configurados com S3/AV reais e fixtures sintéticas:
$env:E2E_ISOLATED_SYNTHETIC='true'
node qa/automation/storage-flow.cjs
```

O checker registra falha se OpenBao/MinIO/ClamAV não responderem. O fluxo de storage só grava sucesso depois de upload/download/checksum, negação anônima, uso único, expiração e EICAR reais. O bootstrap não contém credenciais de serviços fictícios.

Continuam dependendo de ambiente e pessoas: Asaas sandbox e dez callbacks, mídia E2EE/TURN em navegadores/dispositivos, Receita Saúde oficial, pareceres clínico/jurídico/fiscal, assinatura ICP-Brasil/PDF-A, imagem final assinada, DAST/pentest, screen reader/participantes, carga 60min/soak24h e restore banco+objetos+KMS com RPO/RTO medidos. Não houve push, deploy remoto ou liberação de produção.
