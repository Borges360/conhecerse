# ADR-016 — Implementação e adaptação ao laboratório

Data: 13/10/2026. Complementa as decisões normativas de DECISOES.md e SPECIFICACAO_TECNICA.md; não modifica os invariantes clínicos.

Spring Boot 4.1.1 e Spring Modulith 2.1.1 sobre Java 21, persistência Spring JDBC (sem entidades expostas), Flyway, Next.js com BFF OIDC/PKCE e sessão opaca persistida no PostgreSQL. API aceita bearer apenas do cliente BFF com audience validada. O contrato HTTP externo continua o cookie do BFF.

O laboratório usa cinco Deployments (web, api, worker, IAM e antivírus) e PostgreSQL externo no db01. Migrations são executadas em etapa separada com papel proprietário; API/worker usam papel sem ownership/BYPASSRLS. Keycloak, OpenBao e bucket privado são dependências, não substituídos por mocks. LiveKit de produção requer dois nós próprios de rede pública e Redis HA; não implantar hostNetwork no laboratório, conforme QUICKSTART.

Correções do baseline: deduplicação de notificações com NULLS NOT DISTINCT; notas rascunho restritas ao autor; professional VERIFIED obrigatório em RLS; documentos revogados não liberam anexos; vínculo assistencial não libera notas históricas anteriores à transferência; seleção de pacientes/agenda/financeiro limitada também por RLS. Contexto SET LOCAL estabelecido dentro da transação.

OpenAPI foi estendido para ações necessárias que estavam apenas no texto: configuração de disponibilidade e preços, revisão CRP, vínculos do portal, templates e integrações, revisão DSR e gerenciamento de conteúdo. Alterações estão no gerador canônico. Aprovações clínicas/jurídicas são registros humanos; configuração do laboratório não constitui homologação.

Identidade: Conhecer-se é o nome da plataforma; o consultório Paola Gularte preserva sua assinatura visual. Azul-noite, azul intenso, azul suave, areia e grafite são derivados da prancha fornecida. Fontes Playfair Display e Montserrat são hospedadas localmente, sem enviar dados de navegação a um provedor de fontes.

Atualização de segurança: Trivy detectou dependências transitivas vulneráveis na linha anterior. A implementação migrou para Spring Framework 7.0.9/Jackson 3, com Jackson 3.2.3, compatibilidade Jackson 2.22.3 e Tomcat 11.0.25 fixados. O contrato JSON, AAD e migrations permanecem versionados; a suíte completa e OIDC real devem passar novamente após essa troca. Referências: https://spring.io/security/cve-2026-47884/ e https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide.
