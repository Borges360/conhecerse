"""Versioned metadata-only event contracts. No patient narrative or contact fields."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVENTS = {
    **{name: 'appointment' for name in (
        'AppointmentRequested', 'AppointmentScheduled', 'AppointmentConfirmed',
        'AppointmentStarted', 'AppointmentCancelled', 'AppointmentRescheduled',
        'AppointmentCompleted', 'AppointmentNoShow')},
    'PatientInvited': 'patient', 'ClinicalNoteSigned': 'clinicalNote',
    'InvoiceIssued': 'invoice', 'PaymentStateUpdated': 'invoice',
    'DocumentIssued': 'document', 'DocumentRevoked': 'document',
    'MaterialShared': 'content', 'ContentREVIEWED': 'content',
    'ContentPUBLISHED': 'content', 'ContentARCHIVED': 'content',
    'DailyReportRequested': 'report', 'PrivacyRequestCreated': 'privacyRequest',
}
UUID = {'type': 'string', 'format': 'uuid'}
schema = {
    '$schema': 'https://json-schema.org/draft/2020-12/schema',
    '$id': 'urn:conhecerse:events:v1', 'title': 'Conhecer-se integration event v1',
    'type': 'object',
    'required': ['eventId', 'eventType', 'eventVersion', 'tenantId', 'aggregateType',
                 'aggregateId', 'aggregateVersion', 'occurredAt', 'correlationId', 'payload'],
    'properties': {
        'eventId': UUID, 'eventType': {'enum': list(EVENTS)},
        'eventVersion': {'const': 1}, 'tenantId': UUID,
        'aggregateType': {'type': 'string'}, 'aggregateId': UUID,
        'aggregateVersion': {'type': 'integer', 'minimum': 1},
        'occurredAt': {'type': 'string', 'format': 'date-time'}, 'correlationId': UUID,
        'payload': {
            'type': 'object',
            'patternProperties': {'^[A-Za-z]+Id$': UUID},
            'properties': {'sha256': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}},
            'additionalProperties': False,
        },
    },
    'allOf': [{
        'if': {'properties': {'eventType': {'const': name}}},
        'then': {'properties': {
            'aggregateType': {'const': aggregate},
            'payload': {'required': [aggregate + 'Id']},
        }},
    } for name, aggregate in EVENTS.items()],
}
content = json.dumps(schema, ensure_ascii=False, indent=2) + '\n'
target = ROOT / 'contracts/events/v1/envelope.schema.json'
parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
if args.check:
    if not target.exists() or target.read_text(encoding='utf-8') != content:
        raise SystemExit('Event schema is stale; run python scripts/generate_events.py')
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')
print(f'{len(EVENTS)} event contracts v1 synchronized.')
