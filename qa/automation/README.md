# Jornadas adicionais com OIDC real

Complementação: `completion-flow.cjs` utiliza a sessão OIDC local e exercita cadastro fiscal/endereço, perfil público, protocolo remoto e axe nos novos painéis. `storage-flow.cjs` requer MinIO/OpenBao/ClamAV reais configurados, valida upload/download/checksum, EICAR, autenticação, uso único e TTL. Nenhum dos dois registra sucesso quando dependências ou verificações falham. Consulte `../../docs/IMPLEMENTACAO_PENDENCIAS.md`.

`oidc-flow.cjs` executa login no Keycloak, cadastro TOTP quando necessário, callback PKCE, verifica cookie HttpOnly/sessão sem bearer e cria organização pela interface. `vertical-flow.cjs` usa a sessão para cadastrar/revisar profissional, disponibilidade/preço, paciente, agenda, nota assinada, fatura com pagamento manual, conteúdo revisado/publicado e negação CSRF.

Somente localhost, ambiente exclusivo e dados sintéticos. Suba Keycloak/PostgreSQL/API/Next com `APP_ENV=test`, KMS de teste e origem/issuer/client coerentes; nunca utilize dados ou contas reais. Os serviços devem estar prontos. A fixture precisa de `username`, `email`, `password` em `.local/oidc-fixture.json`; o teste registra seu segredo TOTP no mesmo arquivo privado. Não publicar fixture, storageState, tokens ou dumps como artefatos.

Para gerar usuário sintético via Admin API local, forneça `.local/oidc-runtime.env` com `APP_ENV=test`, `OIDC_ISSUER`, `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD`, sem imprimir valores:

```powershell
$env:E2E_ISOLATED_SYNTHETIC='true'
$env:E2E_BASE_URL='http://127.0.0.1:3021'
python qa/automation/provision-user.py
pnpm test:oidc
```

Padrão de navegador Chrome; em CI com Chromium instalado, `PLAYWRIGHT_CHANNEL=chromium`. Evidência sanitizada em `.local/oidc-evidence.json` e `.local/vertical-evidence.json`; copiar somente esses JSONs após revisão. Pagamento desse cenário é um lançamento manual sintético, não uma chamada ao Asaas. Arquivos/antivírus, envio de mensagens e mídia LiveKit exigem as suítes de homologação com fornecedores.
