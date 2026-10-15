# ADR-017 — Completar lacunas da baseline sem presumir homologação

Data: 14/10/2026. Solicitação de engenharia: `docs/IMPLEMENTACAO_PENDENCIAS.md` (ISSUE-PSC-017).

## Decisão de implementação

Adicionar os campos ORG/PAT e workflows obrigatórios ao contrato, persistência, serviços, workers e produto. Migrações V009..V011 são expansivas; V001..V008 permanecem intactas. Rollback de aplicação preserva tabelas e dados novos; não executar down migration ou apagar evidência.

O responsável fiscal e o endereço da organização são cifrados e visíveis somente à administração. Endereço do paciente é opcional. Contato de emergência exige finalidade e vínculo clínico; só profissional vinculado recebe seu conteúdo. Disponibilidade profissional e exceções continuam em vigor, intersectadas com horários/feriados da organização e limite diário transacional por profissional.

Liberação clínica ao responsável ocorre por pacotes individualizados, após revisão jurídica e clínica independentes, com expiração e revogação. Essa revisão não acrescenta permissão de leitura direta de notas ou material de teste. A decisão preserva INV-02, INV-11 e o comportamento conservador de PAT-005. Nenhuma decisão legal ou clínica é aprovada por esta ADR.

Pacotes de acesso usam PDF; portabilidade usa JSON estruturado. O profissional seleciona notas assinadas e prepara conteúdo, confere o arquivo e registra sua revisão. O destinatário precisa ser titular vinculado ou responsável com revisão válida. A liberação dura até sete dias; tickets são autenticados, de uso único e duram 60 segundos. Conclusão só é registrada depois de servir os bytes ao destinatário. O prazo de 15 dias é um SLA operacional configurado na persistência, não uma conclusão jurídica sobre prazos legais.

Acesso emergencial interno concede apenas leitura de narrativas assinadas para um paciente. Solicitante, aprovador de segurança e aprovador clínico são pessoas distintas; o clínico precisa de CRP verificado e vínculo de cuidado. MFA recente, gate de segurança, auditoria e expiração máxima de 15 minutos são obrigatórios. Não amplia `can_access_clinical`, não concede escrita, arquivos ou material de testes. Revogação, expiração ou perda de vínculo de aprovador bloqueiam novas leituras. INV-11 admite somente essa exceção explicitamente revisada, com aprovação de segurança antes do uso.

Protocolo remoto é cifrado e versionado; a última versão precisa de revisão profissional antes da entrada em vídeo. Combinados ficam acessíveis aos participantes autenticados mesmo quando o provedor de vídeo está indisponível; conteúdo de risco/localização/contato é disponibilizado ao profissional do atendimento. Gate clínico e gate de vídeo continuam necessários para teleconsulta.

Mensalidade SaaS utiliza configuração de cofre exclusiva da plataforma (`accountPurpose=PLATFORM_SAAS`), plano/termos aprovados e uma tabela de cobranças própria, sem linhas no ledger de pacientes. Worker concilia a cada seis horas; assinatura gera mensalidades no Asaas. Criação incerta não é repetida automaticamente; bloqueio persistido antes da chamada e referência externa estável evitam duplicidade concorrente. Cancelamento é solicitado e confirmado pelo estado canônico do provedor. Preço e política comercial não são aprovados aqui.

Leituras públicas persistem apenas conteúdo/dia/contagem, sem cookies, IPs, usuários, sessões ou perfil de saúde. Não alegar visitantes únicos nem precisão antifraude.

Assinatura documental verifica bytes PAdES/CMS, digest forte, vínculo do certificado, cadeia e CRLs fornecidas pelo operador. Suporte automático é restrito a uma assinatura `ETSI.CAdES.detached` que cobre o PDF inteiro. Certificado expirado, cadeia/revogação indisponíveis e formatos adicionais exigem revisão humana; adulteração é rejeitada. Homologação do validador e gate documental são exigidos para emitir com `AUTOMATIC_VALIDATED`. Não afirmar PDF/A, ICP-Brasil oficial ou validade jurídica a partir de teste sintético.

Callbacks Resend usam Svix sobre bytes originais e tolerância de cinco minutos; WhatsApp usa HMAC SHA-256 e conferência da conta oficial. Eventos são deduplicados; entrega não regride com callback atrasado, bounce/reclamação suprime envios futuros. Limite diário e reprocessamento humano de falhas confirmadas/DLQ são auditados. Comunicação e assinatura ganham gates explícitos `GATE-COMMUNICATION-01` e `GATE-SIGNATURE-01`.

## Limites de aceite

Credenciais Asaas, LiveKit, Resend e WhatsApp serão cadastradas posteriormente pelo usuário. Docker/MinIO/OpenBao/ClamAV só contam como validados quando os exercícios reais passam. Aprovações jurídico-clínico-fiscais, certificados ICP-Brasil, pentest independente, participantes, carga/soak, restore com KMS e deploy permanecem aceites externos. O código não preenche gates ou evidências automaticamente.
