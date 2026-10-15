-- PSC TECH 2.0 - baseline lógico de PostgreSQL 17+.
-- Executar por Flyway com papel migrator. Runtime role deve ser diferente, sem BYPASSRLS/ownership.
-- Ajustes de migration (permissões, policy grants, envelope encryption) são parte do trabalho de implementação.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA IF NOT EXISTS app;

CREATE TYPE app.member_role AS ENUM ('OWNER','ADMIN','PSYCHOLOGIST','SECRETARY','BILLING','PATIENT','GUARDIAN');
CREATE TYPE app.appointment_status AS ENUM ('REQUESTED','SCHEDULED','CONFIRMED','IN_PROGRESS','COMPLETED','NO_SHOW','CANCELLED');
CREATE TYPE app.reschedule_status AS ENUM ('PENDING','ACCEPTED','DECLINED','EXPIRED','WITHDRAWN');
CREATE TYPE app.invoice_status AS ENUM ('DRAFT','OPEN','PARTIALLY_PAID','PAID','OVERDUE','CANCELLED','REFUNDED','DISPUTED');
CREATE TYPE app.payment_status AS ENUM ('PENDING','AUTHORIZED','CONFIRMED','SETTLED','FAILED','REFUNDED','CHARGEBACK');
CREATE TYPE app.document_status AS ENUM ('DRAFT','PENDING_SIGNATURE','ISSUED','REVOKED');
CREATE TYPE app.note_status AS ENUM ('DRAFT','SIGNED');
CREATE TYPE app.receipt_status AS ENUM ('RECEIPT_PENDING','EXTERNAL_ISSUED','NOT_APPLICABLE','REVIEW_REQUIRED','VOIDED_EXTERNAL');
CREATE TYPE app.notification_status AS ENUM ('QUEUED','SENT','DELIVERED','FAILED','SUPPRESSED');

CREATE TABLE app.auth_users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), oidc_sub text UNIQUE NOT NULL,
 verified_email_hmac bytea UNIQUE, created_at timestamptz NOT NULL DEFAULT now(),
 disabled_at timestamptz, CHECK (length(oidc_sub) BETWEEN 3 AND 256)
);
CREATE TABLE app.organizations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), slug text UNIQUE NOT NULL,
 display_name text NOT NULL, legal_name text, timezone text NOT NULL DEFAULT 'America/Sao_Paulo',
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('PENDING','ACTIVE','SUSPENDED','CLOSED')),
 tax_person_type text NOT NULL DEFAULT 'PJ' CHECK(tax_person_type IN('PF','PJ')),
 settings_version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app.organization_memberships (
 tenant_id uuid NOT NULL REFERENCES app.organizations(id), id uuid NOT NULL DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES app.auth_users(id), roles app.member_role[] NOT NULL,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('INVITED','ACTIVE','SUSPENDED','REVOKED')),
 joined_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,user_id), CHECK(cardinality(roles)>0)
);
CREATE TABLE app.tenant_settings_versions (
 tenant_id uuid NOT NULL REFERENCES app.organizations(id), id uuid NOT NULL DEFAULT gen_random_uuid(),
 version int NOT NULL CHECK(version>0), payload jsonb NOT NULL,
 created_by uuid NOT NULL REFERENCES app.auth_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,version)
);
CREATE TABLE app.professionals (
 tenant_id uuid NOT NULL REFERENCES app.organizations(id), id uuid NOT NULL DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES app.auth_users(id), display_name text NOT NULL,
 crp_uf char(2) NOT NULL, crp_number varchar(25) NOT NULL,
 verification_status text NOT NULL DEFAULT 'PENDING' CHECK(verification_status IN('PENDING','VERIFIED','REJECTED','SUSPENDED')),
 public_profile_enabled boolean NOT NULL DEFAULT false,
 public_bio text, appointment_duration_min int NOT NULL DEFAULT 50 CHECK(appointment_duration_min BETWEEN 30 AND 180),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,user_id),
 UNIQUE(tenant_id,crp_uf,crp_number)
);
CREATE TABLE app.patients (
 tenant_id uuid NOT NULL REFERENCES app.organizations(id), id uuid NOT NULL DEFAULT gen_random_uuid(),
 portal_user_id uuid REFERENCES app.auth_users(id), name_cipher bytea NOT NULL,
 name_lookup_hmac bytea, cpf_cipher bytea, cpf_lookup_hmac bytea, email_cipher bytea,
 email_lookup_hmac bytea, phone_cipher bytea, phone_lookup_hmac bytea, birth_date date,
 communication_preference text NOT NULL DEFAULT 'EMAIL' CHECK(communication_preference IN('EMAIL','WHATSAPP','NONE')),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','INACTIVE','DECEASED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id)
);
CREATE UNIQUE INDEX uq_patients_tenant_cpf ON app.patients(tenant_id,cpf_lookup_hmac) WHERE cpf_lookup_hmac IS NOT NULL;
CREATE TABLE app.guardian_patient_access (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 patient_id uuid NOT NULL, guardian_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 relationship text NOT NULL, access_scope text[] NOT NULL DEFAULT ARRAY['SCHEDULE','BILLING'],
 legal_verified_at timestamptz, review_required boolean NOT NULL DEFAULT true,
 valid_from timestamptz NOT NULL DEFAULT now(), valid_until timestamptz,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 UNIQUE(tenant_id,patient_id,guardian_user_id)
);
CREATE TABLE app.care_assignments (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 patient_id uuid NOT NULL, professional_id uuid NOT NULL, start_at timestamptz NOT NULL DEFAULT now(),
 end_at timestamptz, transfer_approved_at timestamptz,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 CHECK(end_at IS NULL OR end_at>start_at)
);
CREATE INDEX ix_assignments_active ON app.care_assignments(tenant_id,professional_id,patient_id) WHERE end_at IS NULL;
CREATE TABLE app.availability_rules (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 weekday int NOT NULL CHECK(weekday BETWEEN 1 AND 7), local_start time NOT NULL,
 local_end time NOT NULL, timezone text NOT NULL DEFAULT 'America/Sao_Paulo',
 effective_from date NOT NULL, effective_until date,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 CHECK(local_start<local_end)
);
CREATE TABLE app.availability_exceptions (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 from_at timestamptz NOT NULL, to_at timestamptz NOT NULL, reason_code text NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 CHECK(from_at<to_at)
);
CREATE TABLE app.recurrence_series (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 patient_id uuid NOT NULL, rrule text NOT NULL, local_start_time time NOT NULL,
 timezone text NOT NULL, starts_on date NOT NULL, ends_on date, max_count int CHECK(max_count BETWEEN 1 AND 52),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','PAUSED','CANCELLED')),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.appointments (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 patient_id uuid NOT NULL, recurrence_series_id uuid, occurrence_date date,
 rescheduled_from_id uuid, modality text NOT NULL CHECK(modality IN('REMOTE','IN_PERSON')),
 start_at timestamptz NOT NULL, end_at timestamptz NOT NULL,
 busy_range tstzrange NOT NULL,
 status app.appointment_status NOT NULL DEFAULT 'REQUESTED',
 reason_code text, policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 price_cents bigint NOT NULL CHECK(price_cents BETWEEN 0 AND 2000000),
 currency char(3) NOT NULL DEFAULT 'BRL' CHECK(currency='BRL'),
 version int NOT NULL DEFAULT 1, confirmation_at timestamptz,
 actual_end_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,recurrence_series_id) REFERENCES app.recurrence_series(tenant_id,id),
 FOREIGN KEY(tenant_id,rescheduled_from_id) REFERENCES app.appointments(tenant_id,id),
 CHECK(start_at<end_at), CHECK(lower(busy_range)<=start_at AND upper(busy_range)>=end_at),
 CHECK(lower_inc(busy_range) AND NOT upper_inc(busy_range))
);
ALTER TABLE app.appointments ADD CONSTRAINT no_active_appointment_overlap EXCLUDE USING gist (
 tenant_id WITH =, professional_id WITH =, busy_range WITH &&
) WHERE (status IN ('REQUESTED','SCHEDULED','CONFIRMED','IN_PROGRESS'));
CREATE UNIQUE INDEX uq_recurrence_occurrence ON app.appointments(tenant_id,recurrence_series_id,occurrence_date)
 WHERE recurrence_series_id IS NOT NULL AND occurrence_date IS NOT NULL AND rescheduled_from_id IS NULL;
CREATE TABLE app.appointment_history (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), appointment_id uuid NOT NULL,
 previous_status app.appointment_status, new_status app.appointment_status NOT NULL,
 actor_user_id uuid REFERENCES app.auth_users(id), reason_code text, request_id uuid,
 changed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id)
);
CREATE TABLE app.reschedule_requests (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), appointment_id uuid NOT NULL,
 requester_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 desired_start_at timestamptz NOT NULL, desired_end_at timestamptz NOT NULL,
 status app.reschedule_status NOT NULL DEFAULT 'PENDING',
 new_appointment_id uuid, requested_at timestamptz NOT NULL DEFAULT now(),
 resolved_at timestamptz, version int NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id),
 FOREIGN KEY(tenant_id,new_appointment_id) REFERENCES app.appointments(tenant_id,id),
 CHECK(desired_start_at<desired_end_at),
 CHECK ((status='ACCEPTED' AND new_appointment_id IS NOT NULL) OR (status<>'ACCEPTED' AND new_appointment_id IS NULL))
);
CREATE UNIQUE INDEX uq_pending_reschedule ON app.reschedule_requests(tenant_id,appointment_id) WHERE status='PENDING';
CREATE TABLE app.video_rooms (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), appointment_id uuid NOT NULL,
 provider text NOT NULL DEFAULT 'LIVEKIT', provider_room_opaque_id text NOT NULL,
 config_version int NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,appointment_id), UNIQUE(provider,provider_room_opaque_id),
 FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id)
);
CREATE TABLE app.clinical_notes (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 appointment_id uuid, author_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 note_type text NOT NULL DEFAULT 'EVOLUTION', occurred_at timestamptz NOT NULL,
 narrative_cipher bytea NOT NULL, plan_cipher bytea, referrals_cipher bytea,
 encryption_key_id text NOT NULL, encryption_key_version int NOT NULL,
 revision_of_id uuid, version int NOT NULL DEFAULT 1,
 status app.note_status NOT NULL DEFAULT 'DRAFT', content_sha256 bytea,
 signed_at timestamptz, signed_by uuid REFERENCES app.auth_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id),
 FOREIGN KEY(tenant_id,revision_of_id) REFERENCES app.clinical_notes(tenant_id,id),
 CHECK((status='SIGNED' AND signed_at IS NOT NULL AND signed_by IS NOT NULL AND content_sha256 IS NOT NULL)
 OR (status='DRAFT' AND signed_at IS NULL AND signed_by IS NULL))
);
CREATE TABLE app.restricted_test_materials (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 professional_id uuid NOT NULL, object_key_cipher bytea NOT NULL, storage_sha256 bytea NOT NULL,
 encrypted_key_ref text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id)
);
CREATE TABLE app.consents (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid,
 user_id uuid NOT NULL REFERENCES app.auth_users(id), purpose_code text NOT NULL,
 notice_version text NOT NULL, law_basis_code text, action text NOT NULL CHECK(action IN('ACCEPT','WITHDRAW','ACKNOWLEDGE')),
 source text NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.file_assets (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 owner_patient_id uuid, classification text NOT NULL CHECK(classification IN('PUBLIC','INTERNAL','PERSONAL','SENSITIVE_CLINICAL','FINANCIAL')),
 object_key_cipher bytea NOT NULL, filename_cipher bytea, mime_type text NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 26214400), checksum_sha256 bytea NOT NULL,
 kms_key_ref text NOT NULL, malware_scan text NOT NULL CHECK(malware_scan IN('PENDING','CLEAN','REJECTED')),
 uploaded_by uuid REFERENCES app.auth_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,owner_patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.documents (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 author_user_id uuid NOT NULL REFERENCES app.auth_users(id), type text NOT NULL,
 template_version text NOT NULL, status app.document_status NOT NULL DEFAULT 'DRAFT',
 payload_cipher bytea NOT NULL, signed_file_asset_id uuid, content_sha256 bytea,
 issued_at timestamptz, signature_provider text, external_signature_ref text,
 published_to_patient_at timestamptz, version int NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,signed_file_asset_id) REFERENCES app.file_assets(tenant_id,id),
 CHECK((status='ISSUED' AND issued_at IS NOT NULL AND content_sha256 IS NOT NULL) OR status<>'ISSUED')
);
CREATE TABLE app.invoice_payers (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid,
 name_cipher bytea NOT NULL, tax_id_cipher bytea, tax_id_lookup_hmac bytea,
 provider_customer_ref text, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.invoices (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 payer_id uuid NOT NULL, status app.invoice_status NOT NULL DEFAULT 'DRAFT',
 amount_cents bigint NOT NULL CHECK(amount_cents>=0), paid_cents bigint NOT NULL DEFAULT 0 CHECK(paid_cents>=0),
 currency char(3) NOT NULL DEFAULT 'BRL' CHECK(currency='BRL'), due_on date NOT NULL,
 issued_at timestamptz, version int NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,payer_id) REFERENCES app.invoice_payers(tenant_id,id),
 CHECK(paid_cents<=amount_cents)
);
CREATE TABLE app.invoice_items (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), invoice_id uuid NOT NULL,
 appointment_id uuid, description_code text NOT NULL, amount_cents bigint NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,invoice_id) REFERENCES app.invoices(tenant_id,id),
 FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id)
);
CREATE UNIQUE INDEX uq_primary_invoice_for_appointment ON app.invoice_items(tenant_id,appointment_id)
 WHERE appointment_id IS NOT NULL AND description_code='SESSION_PRIMARY';
CREATE TABLE app.payments (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), invoice_id uuid NOT NULL,
 provider text NOT NULL CHECK(provider IN('ASAAS','MANUAL')), provider_payment_ref text,
 status app.payment_status NOT NULL DEFAULT 'PENDING', amount_cents bigint NOT NULL CHECK(amount_cents>=0),
 received_cents bigint NOT NULL DEFAULT 0 CHECK(received_cents>=0),
 method text NOT NULL CHECK(method IN('PIX','BOLETO','CREDIT_CARD','MANUAL')),
 manual_reason text, confirmed_at timestamptz, settled_at timestamptz, version int NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,invoice_id) REFERENCES app.invoices(tenant_id,id),
 CHECK(provider<>'MANUAL' OR manual_reason IS NOT NULL)
);
CREATE UNIQUE INDEX uq_psp_payment ON app.payments(tenant_id,provider,provider_payment_ref) WHERE provider_payment_ref IS NOT NULL;
CREATE TABLE app.ledger_entries (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), invoice_id uuid NOT NULL,
 payment_id uuid, source_event_id uuid, entry_kind text NOT NULL CHECK(entry_kind IN('RECEIVED','REFUNDED','CHARGEBACK','MANUAL_ADJUSTMENT')),
 amount_cents bigint NOT NULL, reason text, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,invoice_id) REFERENCES app.invoices(tenant_id,id),
 FOREIGN KEY(tenant_id,payment_id) REFERENCES app.payments(tenant_id,id),
 UNIQUE(tenant_id,source_event_id,entry_kind)
);
CREATE TABLE app.receipt_references (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), payment_id uuid NOT NULL,
 patient_id uuid NOT NULL, status app.receipt_status NOT NULL DEFAULT 'RECEIPT_PENDING',
 official_receipt_id text, external_issued_on date, amount_cents bigint,
 payer_id uuid, declared_by uuid REFERENCES app.auth_users(id),
 evidence_file_id uuid, notes text, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,payment_id) REFERENCES app.payments(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,payer_id) REFERENCES app.invoice_payers(tenant_id,id),
 FOREIGN KEY(tenant_id,evidence_file_id) REFERENCES app.file_assets(tenant_id,id),
 UNIQUE(tenant_id,payment_id),
 CHECK(status<>'EXTERNAL_ISSUED' OR (official_receipt_id IS NOT NULL AND external_issued_on IS NOT NULL AND declared_by IS NOT NULL))
);
CREATE UNIQUE INDEX uq_official_receipt ON app.receipt_references(tenant_id,official_receipt_id) WHERE official_receipt_id IS NOT NULL;
CREATE TABLE app.notification_templates (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), template_code text NOT NULL,
 version int NOT NULL, channel text NOT NULL CHECK(channel IN('EMAIL','WHATSAPP')),
 body_cipher bytea NOT NULL, is_marketing boolean NOT NULL DEFAULT false,
 approved_provider_template_ref text, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,template_code,version,channel)
);
CREATE TABLE app.notifications (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL, recipient_user_id uuid REFERENCES app.auth_users(id),
 patient_id uuid, template_id uuid NOT NULL, channel text NOT NULL CHECK(channel IN('EMAIL','WHATSAPP')),
 status app.notification_status NOT NULL DEFAULT 'QUEUED',
 provider_message_ref text, sent_at timestamptz, delivered_at timestamptz, failure_code text,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,template_id) REFERENCES app.notification_templates(tenant_id,id),
 UNIQUE(tenant_id,event_id,recipient_user_id,patient_id,template_id,channel)
);
CREATE TABLE app.content_entries (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), entry_type text NOT NULL CHECK(entry_type IN('BLOG','NEWSLETTER','MATERIAL')),
 slug text, status text NOT NULL CHECK(status IN('DRAFT','REVIEW','PUBLISHED','ARCHIVED')),
 title text NOT NULL, body_sanitized_html text, author_user_id uuid REFERENCES app.auth_users(id),
 visibility text NOT NULL CHECK(visibility IN('PUBLIC','PRIVATE_TEAM','PATIENT_ASSIGNED','PATIENT_SHARED')),
 published_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,slug)
);
CREATE TABLE app.content_shares (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), content_id uuid NOT NULL,
 patient_id uuid NOT NULL, shared_by uuid NOT NULL REFERENCES app.auth_users(id),
 valid_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,content_id) REFERENCES app.content_entries(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id), UNIQUE(tenant_id,content_id,patient_id)
);
CREATE TABLE app.privacy_requests (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 request_type text NOT NULL CHECK(request_type IN('ACCESS','RECTIFY','PORTABILITY','ERASURE','REVIEW')),
 status text NOT NULL CHECK(status IN('OPEN','IN_REVIEW','FULFILLED','DENIED_WITH_REASON')),
 requested_by uuid NOT NULL REFERENCES app.auth_users(id), opened_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz, decision_code text, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.daily_reports (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), local_date date NOT NULL,
 report_type text NOT NULL DEFAULT 'DAILY_AGENDA', timezone text NOT NULL,
 generated_at timestamptz, status text NOT NULL CHECK(status IN('PENDING','GENERATED','DELAYED','FAILED')),
 aggregate_json jsonb, PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,local_date,report_type)
);
CREATE TABLE app.audit_events (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), occurred_at timestamptz NOT NULL DEFAULT now(),
 actor_user_id uuid, action_code text NOT NULL, entity_type text NOT NULL, entity_id uuid,
 correlation_id uuid, success boolean NOT NULL, reason_code text,
 previous_hash bytea, event_sha256 bytea NOT NULL, PRIMARY KEY(tenant_id,id)
);
CREATE TABLE app.outbox_events (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 event_type text NOT NULL, event_version int NOT NULL, aggregate_type text NOT NULL,
 aggregate_id uuid NOT NULL, aggregate_version int NOT NULL, correlation_id uuid,
 payload jsonb NOT NULL, status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PROCESSING','DONE','DLQ')),
 attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 locked_until timestamptz, occurred_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id)
);
CREATE INDEX ix_outbox_ready ON app.outbox_events(status,next_attempt_at) WHERE status IN('PENDING','PROCESSING');
CREATE TABLE app.consumed_events (
 tenant_id uuid NOT NULL, consumer_name text NOT NULL, event_id uuid NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,consumer_name,event_id)
);
CREATE TABLE app.inbox_events (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), provider text NOT NULL,
 provider_event_id text NOT NULL, payload_cipher bytea NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PROCESSING','DONE','IGNORED','DLQ')),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,provider,provider_event_id)
);
CREATE TABLE app.provider_requests (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), provider text NOT NULL,
 action text NOT NULL, aggregate_id uuid NOT NULL, idempotency_key uuid NOT NULL,
 request_hash bytea NOT NULL, external_reference text, status text NOT NULL DEFAULT 'PENDING'
 CHECK(status IN('PENDING','IN_FLIGHT','SUCCEEDED','UNKNOWN','FAILED')),
 attempts int NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,provider,action,idempotency_key)
);
CREATE TABLE app.http_idempotency (
 tenant_id uuid NOT NULL, actor_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 route_key text NOT NULL, idempotency_key uuid NOT NULL, request_sha256 bytea NOT NULL,
 result_status int, response_cipher bytea, expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,actor_user_id,route_key,idempotency_key)
);
CREATE TABLE app.jobs (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), job_type text NOT NULL,
 natural_key text NOT NULL, payload jsonb NOT NULL, status text NOT NULL DEFAULT 'PENDING'
 CHECK(status IN('PENDING','PROCESSING','DONE','DLQ')),
 attempts int NOT NULL DEFAULT 0, run_after timestamptz NOT NULL DEFAULT now(),
 locked_until timestamptz, PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,job_type,natural_key)
);
CREATE INDEX ix_jobs_ready ON app.jobs(status,run_after) WHERE status IN('PENDING','PROCESSING');

-- Imutabilidade de notas clínicas assinadas e documentos emitidos: alterações somente por novos registros/adendos.
CREATE OR REPLACE FUNCTION app.forbid_signed_note_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.status='SIGNED' THEN RAISE EXCEPTION 'signed note cannot be deleted'; END IF;
 IF TG_OP='UPDATE' AND OLD.status='SIGNED' THEN RAISE EXCEPTION 'signed note cannot be updated'; END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER tg_clinical_notes_immutable BEFORE UPDATE OR DELETE ON app.clinical_notes
FOR EACH ROW EXECUTE FUNCTION app.forbid_signed_note_mutation();
CREATE OR REPLACE FUNCTION app.forbid_issued_doc_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.status IN('ISSUED','REVOKED') THEN RAISE EXCEPTION 'issued document cannot be deleted'; END IF;
 IF TG_OP='UPDATE' AND OLD.status IN('ISSUED','REVOKED') THEN
   -- After issue: allow exactly one publication timestamp OR a revocation state change.
   -- Content, signed PDF, author, signature reference and hash always remain immutable.
   IF OLD.status='REVOKED' OR NOT (
     (NEW.status='REVOKED' AND NEW.published_to_patient_at IS NOT DISTINCT FROM OLD.published_to_patient_at)
     OR (NEW.status='ISSUED' AND OLD.published_to_patient_at IS NULL
         AND NEW.published_to_patient_at IS NOT NULL)
   ) OR NOT (
     (to_jsonb(NEW)-'status'-'published_to_patient_at') =
     (to_jsonb(OLD)-'status'-'published_to_patient_at')
   ) THEN
     RAISE EXCEPTION 'issued document is immutable';
   END IF;
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER tg_documents_immutable BEFORE UPDATE OR DELETE ON app.documents
FOR EACH ROW EXECUTE FUNCTION app.forbid_issued_doc_mutation();

CREATE OR REPLACE FUNCTION app.current_tenant_id() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT NULLIF(current_setting('app.tenant_id',true),'')::uuid
$$;
CREATE OR REPLACE FUNCTION app.current_actor_id() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT NULLIF(current_setting('app.actor_id',true),'')::uuid
$$;
-- Executar como SECURITY DEFINER com search_path controlado após review de security.
CREATE OR REPLACE FUNCTION app.can_access_clinical(p_patient uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app, pg_temp AS $$
 SELECT EXISTS(
   SELECT 1 FROM app.care_assignments ca
   JOIN app.professionals p ON (p.tenant_id=ca.tenant_id AND p.id=ca.professional_id)
   JOIN app.organization_memberships m ON (m.tenant_id=p.tenant_id AND m.user_id=p.user_id)
   WHERE ca.tenant_id=app.current_tenant_id() AND ca.patient_id=p_patient
   AND p.user_id=app.current_actor_id() AND 'PSYCHOLOGIST'::app.member_role=ANY(m.roles)
   AND m.status='ACTIVE' AND ca.start_at<=now() AND (ca.end_at IS NULL OR ca.end_at>now())
 )
$$;

-- RLS base para tabelas tenant. Aplicações ainda fazem checagem ABAC no service.
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY[
 'organization_memberships','tenant_settings_versions','professionals','patients','guardian_patient_access',
 'care_assignments','availability_rules','availability_exceptions','recurrence_series',
 'appointments','appointment_history','reschedule_requests','video_rooms','clinical_notes',
 'restricted_test_materials','consents','file_assets','documents','invoice_payers','invoices',
 'invoice_items','payments','ledger_entries','receipt_references','notification_templates',
 'notifications','content_entries','content_shares','privacy_requests','daily_reports',
 'audit_events','outbox_events','consumed_events','inbox_events','provider_requests','http_idempotency','jobs'
 ] LOOP
   EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY',t);
   EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY',t);
   EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',t);
 END LOOP;
END $$;
-- Additional defense-in-depth clinical policies: ALL policies combine permissively by default;
-- replace tenant_isolation policy with *restrictive* clinical authorization policy for these tables.
CREATE POLICY notes_care_scope ON app.clinical_notes AS RESTRICTIVE
 USING (app.can_access_clinical(patient_id)) WITH CHECK(app.can_access_clinical(patient_id));
CREATE POLICY tests_care_scope ON app.restricted_test_materials AS RESTRICTIVE
 USING (app.can_access_clinical(patient_id)) WITH CHECK(app.can_access_clinical(patient_id));
-- `documents` and `file_assets` clinical access must also be checked via service and additional restrictive
-- policies adapted to owner_patient_id/classification; do not permit direct runtime SQL access to these
-- until corresponding restrictive policies and reviewer tests are implemented.
-- `auth_users` and `organizations` must be exposed only via identity/tenancy service methods, not generic routes.
-- Provision grants: migrator owns DDL; API runtime `USAGE` on schema and DML only on allowed tables;
-- no CREATE, no SUPERUSER, no BYPASSRLS, no user management; deny arbitrary SQL from frontend.

-- Extensões de domínio necessárias para fluxos integrais
CREATE TABLE app.integration_connections (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), provider text NOT NULL
 CHECK(provider IN('ASAAS','EMAIL','WHATSAPP','LIVEKIT','DOC_SIGNING')),
 label text NOT NULL, secrets_path text NOT NULL, external_account_ref text,
 status text NOT NULL CHECK(status IN('PENDING','ACTIVE','SUSPENDED','REVOKED')),
 webhook_identifier uuid NOT NULL DEFAULT gen_random_uuid(),
 capabilities jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(webhook_identifier), UNIQUE(tenant_id,provider,label)
);
CREATE TABLE app.action_tokens (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 token_hash bytea NOT NULL, purpose text NOT NULL CHECK(purpose IN('INVITE','APPOINTMENT_CONFIRM','UNSUBSCRIBE')),
 user_id uuid REFERENCES app.auth_users(id), patient_id uuid, appointment_id uuid,
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id),
 UNIQUE(token_hash)
);
CREATE TABLE app.newsletter_subscriptions (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), email_cipher bytea NOT NULL,
 email_lookup_hmac bytea NOT NULL, notice_version text NOT NULL, consent_at timestamptz NOT NULL DEFAULT now(),
 unsubscribed_at timestamptz, proof_ref text, PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,email_lookup_hmac)
);
CREATE TABLE app.public_booking_requests (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 patient_name_cipher bytea NOT NULL, patient_email_cipher bytea NOT NULL,
 requested_start_at timestamptz NOT NULL, requested_end_at timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'NEW' CHECK(status IN('NEW','CONTACTED','ACCEPTED','REJECTED','EXPIRED')),
 request_fingerprint_hmac bytea NOT NULL, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 CHECK(requested_start_at < requested_end_at)
);
CREATE UNIQUE INDEX uq_booking_lead_dedup ON app.public_booking_requests(tenant_id,request_fingerprint_hmac)
 WHERE status IN('NEW','CONTACTED');
CREATE TABLE app.document_templates (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 doc_type text NOT NULL, version text NOT NULL, body_template_cipher bytea NOT NULL,
 author_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 approved_by uuid REFERENCES app.auth_users(id), approved_at timestamptz,
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','APPROVED','RETIRED')),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,doc_type,version)
);
CREATE TABLE app.user_notification_preferences (
 tenant_id uuid NOT NULL, user_id uuid NOT NULL REFERENCES app.auth_users(id),
 appointment_email boolean NOT NULL DEFAULT true,
 appointment_whatsapp boolean NOT NULL DEFAULT false,
 marketing_email boolean NOT NULL DEFAULT false,
 marketing_whatsapp boolean NOT NULL DEFAULT false,
 version int NOT NULL DEFAULT 1, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,user_id)
);
CREATE TABLE app.platform_subscriptions (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(),
 plan_code text NOT NULL, status text NOT NULL CHECK(status IN('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED')),
 started_at timestamptz NOT NULL, expires_at timestamptz,
 payment_provider_subscription_ref text,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,payment_provider_subscription_ref)
);

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['integration_connections','action_tokens','newsletter_subscriptions',
 'public_booking_requests','document_templates','user_notification_preferences','platform_subscriptions'] LOOP
   EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY',t);
   EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY',t);
   EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',t);
 END LOOP;
END $$;

-- Deny-by-default SQL read/write of documents by nonclinicians, even when within the same tenant.
-- Patient reading is allowed ONLY for explicitly published documents associated with authenticated portal identity.
CREATE OR REPLACE FUNCTION app.can_read_published_doc(p_patient uuid, p_published timestamptz)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app,pg_temp AS $$
 SELECT p_published IS NOT NULL AND EXISTS(
  SELECT 1 FROM app.patients p WHERE p.tenant_id=app.current_tenant_id()
   AND p.id=p_patient AND p.portal_user_id=app.current_actor_id()
 )
$$;
CREATE POLICY docs_select_scope ON app.documents AS RESTRICTIVE FOR SELECT
 USING (app.can_access_clinical(patient_id) OR app.can_read_published_doc(patient_id,published_to_patient_at));
CREATE POLICY docs_update_scope ON app.documents AS RESTRICTIVE FOR UPDATE
 USING (app.can_access_clinical(patient_id)) WITH CHECK(app.can_access_clinical(patient_id));
CREATE POLICY docs_insert_scope ON app.documents AS RESTRICTIVE FOR INSERT
 WITH CHECK(app.can_access_clinical(patient_id));
CREATE POLICY docs_delete_block ON app.documents AS RESTRICTIVE FOR DELETE USING (false);

-- Sensitive file assets: scoped by treating professional or explicit document release to patient.
-- Service must also verify link, state, malware_scan and object path before any signed download.
CREATE OR REPLACE FUNCTION app.can_read_patient_asset(p_file uuid, p_patient uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app,pg_temp AS $$
 SELECT app.can_access_clinical(p_patient) OR EXISTS(
   SELECT 1 FROM app.documents d
   JOIN app.patients p ON p.tenant_id=d.tenant_id AND p.id=d.patient_id
   WHERE d.tenant_id=app.current_tenant_id()
     AND d.signed_file_asset_id=p_file AND d.patient_id=p_patient
     AND d.published_to_patient_at IS NOT NULL
     AND p.portal_user_id=app.current_actor_id()
 )
$$;
CREATE POLICY clinical_assets_read_scope ON app.file_assets AS RESTRICTIVE FOR SELECT
 USING (classification<>'SENSITIVE_CLINICAL' OR
        (owner_patient_id IS NOT NULL AND app.can_read_patient_asset(id,owner_patient_id)));
CREATE POLICY clinical_assets_insert_scope ON app.file_assets AS RESTRICTIVE FOR INSERT
 WITH CHECK (classification<>'SENSITIVE_CLINICAL' OR
             (owner_patient_id IS NOT NULL AND app.can_access_clinical(owner_patient_id)));
CREATE POLICY clinical_assets_update_scope ON app.file_assets AS RESTRICTIVE FOR UPDATE
 USING (classification<>'SENSITIVE_CLINICAL' OR
        (owner_patient_id IS NOT NULL AND app.can_access_clinical(owner_patient_id)))
 WITH CHECK (classification<>'SENSITIVE_CLINICAL' OR
        (owner_patient_id IS NOT NULL AND app.can_access_clinical(owner_patient_id)));
CREATE POLICY clinical_assets_delete_block ON app.file_assets AS RESTRICTIVE FOR DELETE USING (false);

-- Complementary immutability for audit/ledger. Corrections require compensating INSERTs.
CREATE OR REPLACE FUNCTION app.append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'append-only table cannot be mutated'; END $$;
CREATE TRIGGER tg_ledger_append_only BEFORE UPDATE OR DELETE ON app.ledger_entries
FOR EACH ROW EXECUTE FUNCTION app.append_only();
CREATE TRIGGER tg_appointment_history_append_only BEFORE UPDATE OR DELETE ON app.appointment_history
FOR EACH ROW EXECUTE FUNCTION app.append_only();
CREATE TRIGGER tg_audit_append_only BEFORE UPDATE OR DELETE ON app.audit_events
FOR EACH ROW EXECUTE FUNCTION app.append_only();
CREATE TRIGGER tg_consent_append_only BEFORE UPDATE OR DELETE ON app.consents
FOR EACH ROW EXECUTE FUNCTION app.append_only();

-- NOTE: Migration role / SQL owners should not be granted to API runtime. Apply grants separately.
-- Explicitly forbid DML from runtime on tables requiring service-only operations except via audited service layer.
-- Security testing MUST prove RLS with live runtime credentials and no owner bypass.

CREATE TABLE app.price_rules (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), professional_id uuid NOT NULL,
 modality text NOT NULL CHECK(modality IN('REMOTE','IN_PERSON')),
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 2000000),
 effective_from date NOT NULL, effective_until date,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,professional_id) REFERENCES app.professionals(tenant_id,id),
 CHECK(effective_until IS NULL OR effective_until >= effective_from)
);
ALTER TABLE app.price_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.price_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.price_rules
 USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id());
-- Price rule overlaps by validity period are rejected in service logic; require calendar-date property tests.
