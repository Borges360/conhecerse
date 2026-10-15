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

Total 3840 MiB. A capacidade efetiva do K3s ainda precisa ser medida; os tetos declarados de outras aplicações não demonstram folga. O Jenkins exige 3840 MiB +1024 MiB de margem além de 2 GiB de reserva do host, consultados no Prometheus antes de migrar. Não aumenta `memory_mb` da VM.

Antes do primeiro build: publicar o código no GitHub; provisionar PostgreSQL com os quatro papéis separados; configurar SMTP no Keycloak; criar chave transit `conhecerse` AES-GCM derivada por contexto de tenant e chave HMAC `conhecerse-video`; conceder token limitado/renovável ao workload; configurar bucket privado, CORS para o frontend, credencial S3 restrita e assinaturas do ClamAV. Os campos vazios de OpenBao/S3 não foram substituídos por segredos de demonstração.

O QUICKSTART determina: **“Publicar no cluster (...) só quando a mensagem atual do operador pedir isso com verbo explícito.”** Portanto os passos abaixo estão documentados, não executados. A partir da raiz de `jenkies`, após esse pedido e revisão das credenciais:

```powershell
.\infra\bin\ansible.ps1 ansible-playbook site.yml --tags conhecerse --limit db01 --check
# Executar o apply Ansible com a mesma tag após conferir o check, conforme a interface do wrapper local.
.\infra\bin\k3s-apply.ps1 -Only secrets
.\infra\bin\k3s-apply.ps1 -Only jenkins
.\infra\bin\gitops-publish.ps1 -Message "Conhecer-se no namespace dev"
.\infra\bin\jenkins-publish.ps1 -Message "Pipeline conhecerse-dev"
```

O primeiro build é do operador: `BRANCH=main`, `SKIP_TEST=false`; `DRY_RUN=true` testa e constrói sem push/migração/deploy. O build de implantação usa `DRY_RUN=false`. Não aplicar a pasta `infra/k3s/dev` diretamente com kubectl. Validar probes, GitOps, OIDC, `/api/ready`, AV e KMS antes de atualizar `ESTADO.md`.

As URLs de intranet são `http://conhecerse.lab` e `http://conhecerse-auth.lab`. São exclusivas de desenvolvimento com dados sintéticos. Produção exige TLS e cookies Secure. Se vídeo for habilitado, configurar também `LIVEKIT_URL` no BFF para a CSP e no backend para o adaptador.

## Produção declarativa

`infra/opentofu`: São Paulo, RDS PostgreSQL 17.11 Multi-AZ privado com backup35d/KMS, S3 privado/versionado, bucket de backup com object lock/KMS próprio, Redis HA com TLS, dois nós LiveKit públicos e ALB HTTPS. Rede/VPC, sub-redes, DNS Route53, certificado ACM, AMI e referências de segredos são entradas obrigatórias. State deve usar backend remoto criptografado e locking; não versionar `tfvars`/state. RDS master é somente para provisionamento: criar `conhecerse_migrator`, `conhecerse_runtime`, `conhecerse_web`, `conhecerse_iam` e banco IAM separado; runtime não usa master.

O segredo de cada nó LiveKit é JSON com `config`, `turnCertificate`, `turnPrivateKey`. Configurar porta7880 atrás do ALB; `rtc.use_external_ip=true`, portas UDP50000–60000, TCP7881; TURN5349/TLS e3478/UDP com hostname/certificado de cada EIP; Redis `use_tls=true`; `room.auto_create=false`, nenhuma credencial/serviço Egress. Certificados TURN são montados como arquivos privados e devem ser renovados com rollout. AMI precisa de Docker, AWS CLI, Python3/PyYAML e SSM. O token de entrada dura120s; o worker encerra salas terminais/expiradas ou com vínculo profissional revogado. Homologar revogação, reconexão, E2EE e limites do LiveKit autogerenciado em navegadores reais antes de liberar o gate.

`infra/helm/conhecerse`: requer tag de release, hosts, TLS Secret, Secrets de web/API/IAM e OpenBao configurado com autenticação Kubernetes para a service account. `releaseApproved=false` bloqueia produção. Produção também exige `imageDigests.web/api/iam/av` válidos, obtidos após scan e assinatura das imagens; o chart usa essas referências OCI imutáveis. A verificação de assinatura/admission deve estar configurada no cluster antes do aceite. API exige `sslmode=verify-full`, issuer/KMS/S3 HTTPS e runtime sem ownership/BYPASSRLS. BFF exige origem HTTPS e cookie Secure. Não usar token raiz do OpenBao; agent renova token restrito. Aplicar least privilege para S3/KMS e cofre, revisar ServiceMonitor/PrometheusRule no cluster, montar CA privada quando necessária.

## Integrações e contingências

No cofre, Asaas: `apiKey` e `webhookToken`, ambiente sandbox/produção revisado. Email: Resend `apiKey` e remetente `from` validado. WhatsApp: token oficial, `phoneNumberId`, templates aprovados e consentimentos. Referências KV seguem `conhecerse/{dev|staging|production}/...`. Ativar uma conexão exige evidência humana e MFA; vídeo, PSP, documentos e fiscal têm gates próprios.

PSP incerto: manter a intenção `UNKNOWN`, consultar a referência externa antes de qualquer nova criação; não repetir cobrança automaticamente sem prova de ausência. Webhook é autenticado e persistido cifrado antes de ACK, depois conciliado pela API canônica. Reembolso/chargeback adicionam movimentos compensatórios. Receita Saúde continua manual, com pagador/beneficiário separados e declaração externa, sem login gov.br.

Vídeo indisponível: orientar o canal previamente combinado, manter agenda e registrar somente metadados. Arquivo inválido não vira READY; quarentena é privada e deve expirar em24h. Nunca enviar texto clínico por email/WhatsApp. Marketing exige confirmação por email; opt-out do portal também revoga a newsletter daquele email no tenant.

Rollback: reverter imagem para a última tag validada, após verificar compatibilidade com as migrações expand/contract. Não editar migrations aplicadas ou apagar histórico. Restore deve ocorrer em ambiente isolado, com banco+objetos+KMS, hashes, consistência e RPO/RTO medidos. Esses exercícios e o aceite operacional ainda são pendentes.
