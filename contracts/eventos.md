# Catálogo de eventos de integração e jobs — v1

## Envelope obrigatório
```json
{
  "eventId":"018f6970-1234-7abc-8def-0123456789ab",
  "eventType":"AppointmentScheduled",
  "eventVersion":1,
  "tenantId":"018f6970-1234-7abc-8def-0123456789ac",
  "aggregateType":"appointment",
  "aggregateId":"018f6970-1234-7abc-8def-0123456789ad",
  "aggregateVersion":3,
  "occurredAt":"2026-10-13T14:00:00Z",
  "correlationId":"a37f322b-41b8-4cb1-a2ae-caf63a5b657f",
  "payload":{"appointmentId":"018f6970-1234-7abc-8def-0123456789ad"}
}
```
Todos os eventos contêm **somente IDs e metadados mínimos**; nunca texto clínico, CPF, email ou segredo. `eventId` global único. Schemas de evento versionados; consumidores rejeitam major desconhecida para DLQ e alertam. Campos adicionais são compatíveis, remoção e mudança semântica exigem nova versão.

Contrato executável v1: `events/v1/envelope.schema.json`, gerado por `scripts/generate_events.py`. Campos de envelope são colunas da outbox; o payload admite IDs e hash, sem dados de contato/narrativa. A implementação acrescenta os eventos `AppointmentStarted`, `DocumentRevoked` e revisão/publicação/arquivamento de conteúdo. O worker rejeita versão major diferente de 1 para DLQ imediatamente, incrementando a métrica monitorada. Ampliação de metadados deve atualizar o schema antes do produtor e manter consumidores compatíveis.

| Evento v1 | Emissor | Consumidores | Dedup | Observações |
|---|---|---|---|---|
| `PatientInvited` | Patients | Comms | eventId + canal | email com link tokenizado |
| `AppointmentRequested` | Scheduling | Comms, Reporting | eventId | sem criar sala até aceitação |
| `AppointmentScheduled` | Scheduling | Video, Comms | eventId | criar configuração de sala idempotente |
| `AppointmentConfirmed` | Scheduling | Comms, Reporting | eventId | confirmação, sem dado clínico |
| `AppointmentCancelled` | Scheduling | Billing, Comms, Reporting | eventId | política snapshot |
| `AppointmentRescheduled` | Scheduling | Comms, Reporting | eventId | oldId/newId |
| `AppointmentCompleted` | Scheduling | Billing, Reporting | eventId | fatura conforme modo |
| `AppointmentNoShow` | Scheduling | Billing, Reporting | eventId | não inferir pelo vídeo |
| `ClinicalNoteSigned` | Clinical | Audit | eventId | hash, sem narrative |
| `InvoiceIssued` | Billing | PSP, Comms | eventId | emitir cobrança remota via job |
| `PaymentStateUpdated` | Billing | Comms, Reporting, Receipts | eventId | sem dados de cartão |
| `DocumentIssued` | Documents | Comms | eventId | link autenticado |
| `MaterialShared` | Content | Comms | eventId | link de portal |
| `DailyReportRequested` | Reporting | Reporting, Comms | tenant+date+version | job local 7:00 |
| `PrivacyRequestCreated` | Patients | Compliance | eventId | fila sem conteúdo |

## Dispatcher e job engine
`outbox_events`: transação junto ao agregado; worker `SKIP LOCKED` lote 100, `locked_until` 2min, `attempts`, `next_attempt_at`, `status`. Dedup consumidores `consumed_events (handler, eventId)` com índice UNIQUE. Tentativas máximas: 10 para eventos internos, 5 para externos transientes, backoff `min(2^attempt * 30s, 6h)` + jitter; DLQ retém payload sanitizado e requer reprocessamento autenticado. Toda ação externa persistirá comando `provider_requests` com idempotência local antes da chamada, e reconciliação de comandos em estado incerto.

## Webhooks PSP
Endpoint inbound deve validar autenticação do PSP com segredo token/headers conforme contrato oficial ativo, HTTPS e rate limit; não assumir assinatura HMAC inexistente. Persistir evento bruto criptografado, validar tipo e `id` exclusivo, ACK rápido. Eventos desconhecidos são aceitos e registrados como ignorados, com alerta de versão. Mudanças de cobrança resultam em consulta ao PSP quando necessário; `RECEIVED`, `REFUNDED`, chargebacks produzem reconciliação via adaptador e lançamentos compensatórios. Ordem temporal dos callbacks **não é confiável**.

## Scheduler determinístico
- 07:00 timezone da organização: evento `DailyReportRequested` para cada data local, unique `(tenant, local_date, type)`.
- A cada 5min: notificações cujo `due_at<=now()` e estado PENDING.
- A cada 6h: cobrança reconciliation em backlog prioritário.
- Diariamente 02:00 local: expandir séries recorrentes até 90 dias mantendo UNIQUE de ocorrência.
- A cada hora: eventos mortos, jobs atrasados, desbalanceamento contábil, convites expirados; não usar cron para apagar prontuários.
- Ao realizar deploy: scheduler com advisory lock ou leader election DB, e todos os jobs idempotentes.
