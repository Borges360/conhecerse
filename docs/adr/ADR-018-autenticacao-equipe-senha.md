# ADR-018 — Autenticação da equipe por senha

Data: 11/11/2026. Solicitação: ISSUE-PSC-018, a decisão de escopo sobre autenticação registrada na [reespecificação das pendências](../REESPECIFICACAO_PENDENCIAS.md#implementação-e-configuração-pendentes).

## Contexto

O baseline `093bade` exigia segundo fator TOTP para todo vínculo de equipe e autenticação recente de até 15 minutos para assinar evolução e para operar o acesso emergencial. A API respondia `STAFF_MFA_REQUIRED` e `RECENT_MFA_REQUIRED`, o download de arquivos repetia a exigência de MFA e o BFF pedia `acr_values=2`. A árvore local removeu essas exigências da especificação, do realm, dos contratos, do código e dos testes, e o ensaio de 17/10/2026 autenticou seis perfis sintéticos somente com senha. Faltava registrar a decisão pelo fluxo da [política de mudança](../../DECISOES.md#política-de-mudança).

## Decisão

Por decisão explícita do responsável pelo projeto, em 11/11/2026, a equipe e o portal autenticam somente com senha no Keycloak via OIDC. A plataforma não exige segundo fator nem autenticação recente para assinar evolução ou operar o acesso emergencial. A decisão vale para todos os ambientes e substitui as menções a MFA no ADR-004 da especificação, em SEC-005, no §2.3, no §4.4 e em UX-01/UX-11.

| Ponto | Baseline `093bade` | Com esta ADR |
| --- | --- | --- |
| SEC-005 | MFA da equipe; OIDC/PKCE centralizado | OIDC/PKCE centralizado |
| Onboarding (§2.3) | Email verificado e MFA para uso clínico | Email verificado |
| Assinatura de evolução (§4.4) | MFA recente, `If-Match`, autor e vínculo ativo | `If-Match`, autor e vínculo ativo |
| Acesso emergencial (SEC-015) | Dupla aprovação e MFA recente em solicitar, aprovar, negar e revogar | Dupla aprovação independente, sem MFA |
| Contratos | `NOTE_AUTHOR_MFA`, `MFA_RECENT_15_MINUTES` e guards `*_MFA` | `NOTE_AUTHOR` e guards sem MFA |
| Realm | Fluxo com OTP e ação `CONFIGURE_TOTP` | `conhecerse-browser` com senha; `VERIFY_EMAIL` como ação padrão |
| API e BFF | `STAFF_MFA_REQUIRED`, `RECENT_MFA_REQUIRED`, `acr_values=2` | Removidos |

Esta ADR não altera estes controles:

- Authorization Code com PKCE, BFF com cookie `HttpOnly`/`Secure`/`SameSite=Lax` e CSRF;
- email verificado, senha com mínimo de 12 caracteres e recuperação pelo Keycloak com token efêmero;
- proteção contra força bruta do realm, com bloqueio temporário após 5 falhas e espera incremental de 60 s até 15 min;
- sessão SSO com 30 min de inatividade e 8 h no máximo, access token de 120 s e refresh token rotacionado sem reutilização;
- RLS/ABAC, vínculo de cuidado e auditoria encadeada.

## Impacto

- **SEC-005 e T-033.** O aceite de autenticação passa a depender de OIDC/PKCE, da proteção contra força bruta, da recuperação e da ausência de enumeração de contas. T-033 precisa de execução no ambiente candidato com o relógio do nó sincronizado.
- **Assinatura de evolução.** Basta uma sessão válida do profissional autor. Com a senha de um profissional comprometida, é possível assinar notas em nome dele enquanto a sessão durar. A assinatura continua sendo evidência interna, com hash canônico e auditoria encadeada, e não equivale a certificado digital.
- **SEC-015 e T-041.** Continuam valendo o solicitante distinto, a aprovação de segurança e a aprovação clínica por pessoas diferentes, a expiração em até 15 min, a leitura restrita a notas assinadas e a auditoria de cada leitura. Uma única senha comprometida não ativa o acesso; três contas autenticadas por senha ativam.
- **Invariantes.** Nenhuma INV muda. INV-02 e INV-11 continuam garantidas por vínculo de cuidado e RLS, e INV-05 não é afetada.
- **Risco residual.** Phishing, reutilização de senha ou credential stuffing contra contas da equipe dão acesso ao conteúdo clínico dos pacientes vinculados à conta comprometida. O aceite de segurança deve avaliar esse risco e os controles compensatórios necessários, como monitoramento e alerta de falhas de login.
- **Dados e migração.** O schema da aplicação não muda. Em realms já inicializados, `scripts/remove-second-factors.py` remove fatores cadastrados, conforme o [guia de operação](../OPERACAO_IMPLEMENTACAO.md#atualização-de-identidade-em-bancos-existentes). Os códigos `STAFF_MFA_REQUIRED` e `RECENT_MFA_REQUIRED` deixam de existir no contrato HTTP.
- **Reversão.** Restaurar a partir de `093bade`: `Actor.mfaAt`/`recentMfa`, as verificações em `ApiController` e `FileDownloadController`, `acr_values`, o fluxo OTP do realm, os contratos e os testes. Cada usuário precisaria cadastrar o TOTP de novo.

## Aprovações pendentes

Esta ADR registra a decisão do projeto e o alinhamento técnico, mas não substitui as aprovações exigidas pela política de mudança. A mudança de política de acesso a dados clínicos exige parecer de DPO/jurídico. A assinatura de evolução exige o responsável técnico clínico, e o risco residual exige aceite de segurança. Até esses pareceres serem anexados com T-033 e T-041 executados no candidato, a liberação de produção continua bloqueada.
