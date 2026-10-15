# Relatório de validação da especificação (13/10/2026)

## Verificações executadas neste pacote
1. YAML interpretado com PyYAML e checagem de referências `$ref` locais OpenAPI.
2. Todas as operações declaradas têm `operationId`, `x-required-permission`, sem códigos HTTP de mutação silenciosos.
3. Todo `x-required-permission` está definido em `contracts/permissions.yaml`.
4. Máquinas de estado possuem estados iniciais, transições e invariantes.
5. DOCX renderizado e inspecionado visualmente (37 páginas); schema e contratos completos ficam no ZIP.

## Ainda necessário para uma implementação segura (não executado neste ambiente)
- Testar **execução real** de migrations `schema.sql` em PostgreSQL 17 e validação de roles/permissions com Testcontainers.
- Gerar contratos JSON Schema para eventos outbox conforme `contracts/eventos.md` e validar interoperabilidade do backend real.
- Executar testes de concorrência (`EXCLUDE` + reschedule), segurança RLS/ABAC e chamadas reais sandbox do PSP e canais.
- Testar cluster LiveKit selfhost em ambiente com host networking, TURN, Redis HA e E2EE navegadores.
- Aprovação jurídica (LGPD, retenção e responsáveis legais), clínica (CFP e documentos), fiscal (Receita Saúde PF/PJ) e operacional.
- Testes OWASP ASVS, pentest independente, backup/restore e stress antes do go-live.

**Não é seguro assumir que DDL, OpenAPI e contratos isoladamente equivalem a código homologado em produção.** O critério de conclusão é a suíte de aceite executada no produto real.
