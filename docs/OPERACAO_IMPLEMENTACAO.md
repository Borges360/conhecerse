# Execução e operação

Fluxos e parâmetros adicionados pela complementação estão em [IMPLEMENTACAO_PENDENCIAS.md](IMPLEMENTACAO_PENDENCIAS.md). Não aprovar automaticamente gates, planos comerciais ou certificados. O Ingress encaminha `/v1/webhooks/communications` diretamente à API; preserve os bytes e headers Svix/Meta para verificar assinaturas.

## Laboratório existente

Arquivos preparados em `C:\Users\felip\Desenvolvimento\jenkies`, somente nas áreas permitidas pelo QUICKSTART: workload, Dockerfile/ConfigMap, RBAC, catálogo Jenkins, topologia, provisionamento PostgreSQL, montagem dos Secrets, DNS explícito e tabela de credenciais. A alteração já existente do operador em `playbooks/ESTADO.md` foi preservada; não foi tratada como evidência de implantação.

| Componente | Nome/porta | Limite |
| --- | --- | --- |
| Next/BFF | `conhecerse:3000` | 256 MiB |
| API | `conhecerse-api:8080` | 512 MiB |
| Worker | `conhecerse-worker:8080` | 512 MiB |
| IAM | `conhecerse-iam:8080` | 512 MiB |
| ClamAV | `conhecerse-av:3310` | 2048 MiB |
| Agentes OpenBao | sidecar `bao-agent` da API e do worker | 2 × 128 MiB |

**Estado conferido em 17/10/2026:** Jenkins #24 aprovou o commit `8661385f5f58fdef442337874e70d4160dd05c7c`, tag `24-8661385`. A esteira validou secret scan Trivy offline, SBOM/SCA e as quatro imagens finais, gate de capacidade preflight, 13 migrações Flyway e rollout dos cinco Deployments. Web, API, worker, IAM e antivírus estão em 1/1; `/` e `/api/ready` respondem 200. O GitOps está `Synced` na revisão `0085c225113760c1b0a8d7c986845c42c051b27b`; a saúde agregada `Degraded` é causada por `publishsys` e `sistemalux`, fora desta aplicação. Consulte [IMPLEMENTACAO_PENDENCIAS.md](IMPLEMENTACAO_PENDENCIAS.md) e a [evidência release24](../ops/evidencias/dev-lab-2026-10-17-release-24.json).

O total de 4096 MiB é o teto declarado dos cinco workloads, incluindo os dois agentes OpenBao. O gate da pipeline é um preflight de capacidade e não mede o aceite de 100 req/s por 60 minutos nem soak de 24 horas. A regra exige 4096 MiB +1024 MiB de margem além de 2 GiB de reserva do host, consultados no Prometheus antes de migrar. Não aumentar `memory_mb` da VM como substituto da medição.

O provisionamento de PostgreSQL, bucket privado, permissões MinIO, chaves Transit e ClamAV foi exercitado no laboratório. Na release24, upload, descriptografia, checksum, negações anônimas/entre tenants, expiração de ticket, EICAR e falha fechada/recuperação do ClamAV passaram com dados sintéticos. A chamada OpenBao `renew-self` retornou `renewable=true`, TTL de 30 dias e política `conhecerse-dev`. O workload ainda usa token estático em Kubernetes Secret, sem auto-auth/renovação automática configurada; indisponibilidade/recuperação do OpenBao, revogação, rotação e restore com descriptografia permanecem pendentes. SMTP do Keycloak também não foi homologado. Os segredos de provedores externos continuam ausentes até o cadastro autorizado. O manifesto atual substitui o token estático pelo agente OpenBao; a troca depende da configuração descrita em [Identidade de workload no OpenBao](#identidade-de-workload-no-openbao-sec-012).

O pedido explícito do usuário autorizou o primeiro deploy de laboratório; esse deploy foi executado e está comprovado pela release24. Para alterações futuras, usar o job e a publicação GitOps oficiais após autorização aplicável. Não aplicar `infra/k3s/dev` diretamente com `kubectl`.

```powershell
.\infra\bin\gitops-publish.ps1 -Message "Atualizar Conhecer-se no namespace dev" -Paths "infra/k3s/dev/conhecerse.yaml"
# Em seguida, executar conhecerse-dev pelo fluxo Jenkins autorizado e conferir a revisão Argo.
```

O Jenkins #24 usou `SKIP_TEST=false` e concluiu a implantação. O DAST passivo não autenticado terminou sem falhas bloqueantes, mas deixou 10 categorias de avisos para revisão contextual; isso não é DAST autenticado, ASVS ou pentest. O gate de capacidade aprovado não substitui os testes de carga especificados. Nunca atualizar o estado do operador ou declarar aceite sem evidência vinculada ao commit/tag; `releaseApproved=false` permanece.

A URL de intranet do laboratório é `http://conhecerse.lab`; a autenticação do Keycloak fica sob `/auth` na mesma origem. É exclusiva de desenvolvimento com dados sintéticos. Produção exige TLS e cookies Secure. Se vídeo for habilitado, configurar também `LIVEKIT_URL` no BFF para a CSP e no backend para o adaptador.

Na medição de 17/10, os cinco pods agendados no nó `k3s01` estavam 60 segundos atrasados em relação ao runner. Revisar a sincronização NTP do nó antes de aceitar expiração de sessão em condições reais.

### Identidade de workload no OpenBao (SEC-012)

API e worker leem o token do OpenBao no arquivo escrito pelo `bao-agent`. Esse sidecar nativo autentica com o token projetado da service account (`audience=openbao`, validade de 1 h) e renova o token restrito. O manifesto do laboratório usa a service account `conhecerse-workload` e o role `conhecerse-dev`; o chart usa `openbao.authRole`. Como a aplicação relê o arquivo a cada operação, a renovação não exige reinício. Sem token, o sidecar não conclui o startup e o pod não fica pronto, ou seja, a falha é fechada.

Antes de publicar o manifesto, operação e segurança configuram o OpenBao com autorização própria, sem uso rotineiro de token raiz:

```sh
bao auth enable kubernetes   # somente se o método ainda não existir
bao write auth/kubernetes/config kubernetes_host=https://kubernetes.default.svc:443
bao write auth/kubernetes/role/conhecerse-dev \
  bound_service_account_names=conhecerse-workload \
  bound_service_account_namespaces=dev \
  audience=openbao \
  token_policies=conhecerse-dev \
  token_no_default_policy=true \
  token_ttl=1h token_max_ttl=24h
```

A service account do OpenBao precisa de `system:auth-delegator` para validar os tokens por TokenReview. Depois do rollout com o agente, revogar o token estático e remover o Secret `dev/conhecerse-bao-token`. Para fechar SEC-012 no candidato, registrar:

- o login `auth/kubernetes` no log do agente e em `bao token lookup`;
- renovação durante mais de um TTL;
- falha fechada após revogar o accessor ativo, seguida de nova autenticação;
- falha fechada e recuperação com o OpenBao indisponível;
- leitura de objetos existentes depois da rotação Transit e do restore.

### Assinatura e admissão de imagens (SEC-013)

A esteira executa a cadeia de imagens em uma única referência OCI:

1. O build grava o digest de cada imagem em `image-digests.env`.
2. Trivy analisa e gera o SBOM por digest.
3. O cosign v2.6.5 assina cada digest com `--tlog-upload=false`, sem envio ao log público, e verifica a assinatura com `cosign.pub`.
4. A migração e o rollout usam a mesma referência.
5. `rollout-images.txt` compara a spec dos Deployments com o `imageID` dos pods em execução.

O Jenkins precisa das credenciais `conhecerse-cosign-key`, um Secret file com a chave privada cifrada gerada por `cosign generate-key-pair`, e `conhecerse-cosign-password`, um Secret text. Se faltarem, o estágio de assinatura falha e o deploy não acontece. A chave privada nunca entra em Git, values ou logs; somente `cosign.pub` é arquivado. No laboratório, a verificação ocorre na esteira.

No ambiente candidato, a verificação ocorre na admissão:

- instalar o Sigstore policy-controller v0.15.x e manter `no-match-policy=deny`;
- rotular o namespace com `policy.sigstore.dev/include=true`;
- informar em `imageSignature.publicKey` o conteúdo de `cosign.pub`.

O chart cria uma `ClusterImagePolicy` para `<registry>/conhecerse*` com essa chave e outra que admite o agente OpenBao somente pelo digest fixado. Se o namespace já existir sem o rótulo, o chart recusa a instalação. Para fechar SEC-013, registrar o digest do build, do scan, da assinatura e do rollout, além de uma tentativa de admissão negada com imagem sem assinatura confiável.

## Produção declarativa

`infra/opentofu`: São Paulo, RDS PostgreSQL 17.11 Multi-AZ privado com backup35d/KMS, S3 privado/versionado, bucket de backup com object lock/KMS próprio, Redis HA com TLS, dois nós LiveKit públicos e ALB HTTPS. Rede/VPC, sub-redes, DNS Route53, certificado ACM, AMI e referências de segredos são entradas obrigatórias. State deve usar backend remoto criptografado e locking; não versionar `tfvars`/state. RDS master é somente para provisionamento: criar `conhecerse_migrator`, `conhecerse_runtime`, `conhecerse_web`, `conhecerse_iam` e banco IAM separado; runtime não usa master.

O segredo de cada nó LiveKit é JSON com `config`, `turnCertificate`, `turnPrivateKey`. Configurar porta7880 atrás do ALB; `rtc.use_external_ip=true`, portas UDP50000–60000, TCP7881; TURN5349/TLS e3478/UDP com hostname/certificado de cada EIP; Redis `use_tls=true`; `room.auto_create=false`, nenhuma credencial/serviço Egress. Certificados TURN são montados como arquivos privados e devem ser renovados com rollout. AMI precisa de Docker, AWS CLI, Python3/PyYAML e SSM. O token de entrada dura120s; o worker encerra salas terminais/expiradas ou com vínculo profissional revogado. Homologar revogação, reconexão, E2EE e limites do LiveKit autogerenciado em navegadores reais antes de liberar o gate.

`infra/helm/conhecerse`: requer tag de release, hosts, TLS Secret, Secrets de web/API/IAM e OpenBao configurado com autenticação Kubernetes para a service account. `releaseApproved=false` bloqueia produção. Staging e produção exigem `imageDigests.web/api/iam/av` válidos, obtidos após scan e assinatura das imagens, e `imageSignature.enforce=true` com a chave pública cosign; o chart usa essas referências OCI imutáveis e cria as políticas de [admissão](#assinatura-e-admissão-de-imagens-sec-013). API exige `sslmode=verify-full`, issuer/KMS/S3 HTTPS e runtime sem ownership/BYPASSRLS. BFF exige origem HTTPS e cookie Secure. Não usar token raiz do OpenBao; agent renova token restrito. Aplicar least privilege para S3/KMS e cofre, revisar ServiceMonitor/PrometheusRule no cluster, montar CA privada quando necessária.

## Integrações e contingências

No cofre, Asaas: `apiKey` e `webhookToken`, ambiente sandbox/produção revisado. Email: Resend `apiKey` e remetente `from` validado. WhatsApp: token oficial, `phoneNumberId`, templates aprovados e consentimentos. Referências KV seguem `conhecerse/{dev|staging|production}/...`. Ativar uma conexão exige evidência humana; vídeo, PSP, documentos e fiscal têm gates próprios.

PSP incerto: manter a intenção `UNKNOWN`, consultar a referência externa antes de qualquer nova criação; não repetir cobrança automaticamente sem prova de ausência. Webhook é autenticado e persistido cifrado antes de ACK, depois conciliado pela API canônica. Reembolso/chargeback adicionam movimentos compensatórios. Receita Saúde continua manual, com pagador/beneficiário separados e declaração externa, sem login gov.br.

Vídeo indisponível: orientar o canal previamente combinado, manter agenda e registrar somente metadados. Arquivo inválido não vira READY; quarentena é privada e deve expirar em24h. Nunca enviar texto clínico por email/WhatsApp. Marketing exige confirmação por email; opt-out do portal também revoga a newsletter daquele email no tenant.

Rollback: reverter imagem para a última tag validada, após verificar compatibilidade com as migrações expand/contract. Não editar migrations aplicadas ou apagar histórico. Restore deve ocorrer em ambiente isolado, com banco+objetos+KMS, hashes, consistência e RPO/RTO medidos. Esses exercícios e o aceite operacional ainda são pendentes.

## Atualização de identidade em bancos existentes

O realm usa login com senha, mantendo verificação de email, PKCE e sessão BFF. A importação do realm não atualiza um banco já inicializado. Para aplicar a remoção dos fatores antigos, configure `KEYCLOAK_URL` (incluindo `/auth`, quando aplicável), `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` e, opcionalmente, `KEYCLOAK_REALM`; execute `python scripts/remove-second-factors.py`. O script remove os desafios dos fluxos personalizados, ações de cadastro e credenciais associadas de todos os usuários do realm, verifica a ausência das credenciais e pode ser repetido. A API e o frontend devem usar as versões atualizadas deste código.
