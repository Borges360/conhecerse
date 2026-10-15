-- Corrections documented in ADR-011. Roles are provisioned independently of migrations.
ALTER TABLE app.patients ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.patients ADD COLUMN social_name_cipher bytea;
ALTER TABLE app.professionals ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.professionals ADD COLUMN verification_reviewed_by uuid REFERENCES app.auth_users(id);
ALTER TABLE app.professionals ADD COLUMN verification_evidence text;
ALTER TABLE app.invoice_payers ADD COLUMN person_type text NOT NULL DEFAULT 'PF' CHECK(person_type IN('PF','PJ'));
ALTER TABLE app.documents ADD COLUMN signature_verification_evidence text;
ALTER TABLE app.receipt_references ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.content_entries ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.file_assets ADD COLUMN status text NOT NULL DEFAULT 'UPLOADING' CHECK(status IN('UPLOADING','READY','REJECTED'));
ALTER TABLE app.file_assets ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.file_assets ADD COLUMN original_checksum_sha256 bytea;
ALTER TABLE app.recurrence_series ADD COLUMN expanded_until date;
ALTER TABLE app.recurrence_series ADD COLUMN duration_minutes int NOT NULL DEFAULT 50;
ALTER TABLE app.recurrence_series ADD COLUMN modality text NOT NULL DEFAULT 'REMOTE';
ALTER TABLE app.recurrence_series ADD COLUMN price_cents bigint NOT NULL DEFAULT 0;
ALTER TABLE app.inbox_events ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE app.inbox_events ADD COLUMN locked_until timestamptz;
ALTER TABLE app.inbox_events ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE app.notifications ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE app.notifications ADD COLUMN due_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE app.notifications ADD COLUMN locked_until timestamptz;
ALTER TABLE app.notifications ADD COLUMN request_reference uuid;
DO $$ DECLARE c text; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='app.notifications'::regclass AND contype='u' LOOP
 EXECUTE format('ALTER TABLE app.notifications DROP CONSTRAINT %I',c);
 END LOOP;
END $$;
CREATE UNIQUE INDEX uq_notification_delivery ON app.notifications(tenant_id,event_id,recipient_user_id,patient_id,template_id,channel) NULLS NOT DISTINCT;

CREATE TABLE app.download_tickets (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), file_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES app.auth_users(id), token_hash bytea NOT NULL,
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 PRIMARY KEY(tenant_id,id), UNIQUE(token_hash), FOREIGN KEY(tenant_id,file_id) REFERENCES app.file_assets(tenant_id,id)
);
ALTER TABLE app.download_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.download_tickets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.download_tickets USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id());

CREATE SCHEMA IF NOT EXISTS bff;
CREATE TABLE bff.sessions (
 id_hash bytea PRIMARY KEY, encrypted_tokens bytea NOT NULL, csrf_token text NOT NULL,
 subject text NOT NULL, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sessions_expiry ON bff.sessions(expires_at);

CREATE TABLE app.integration_routes (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES app.organizations(id), provider text NOT NULL);
CREATE FUNCTION app.sync_integration_route() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=app,pg_temp AS $$
BEGIN
 INSERT INTO app.integration_routes(id,tenant_id,provider) VALUES(NEW.webhook_identifier,NEW.tenant_id,NEW.provider)
 ON CONFLICT(id) DO UPDATE SET provider=EXCLUDED.provider;
 RETURN NEW;
END $$;
CREATE TRIGGER tg_integration_route AFTER INSERT OR UPDATE ON app.integration_connections FOR EACH ROW EXECUTE FUNCTION app.sync_integration_route();

CREATE OR REPLACE FUNCTION app.is_staff(allowed text[]) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM app.organization_memberships m JOIN app.organizations o ON o.id=m.tenant_id
 WHERE m.tenant_id=app.current_tenant_id() AND m.user_id=app.current_actor_id() AND m.status='ACTIVE' AND o.status='ACTIVE' AND m.roles::text[] && allowed)
$$;
CREATE OR REPLACE FUNCTION app.can_access_clinical(p_patient uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT app.is_staff(ARRAY['PSYCHOLOGIST']) AND EXISTS(
 SELECT 1 FROM app.care_assignments ca JOIN app.professionals p ON p.tenant_id=ca.tenant_id AND p.id=ca.professional_id
 WHERE ca.tenant_id=app.current_tenant_id() AND ca.patient_id=p_patient AND p.user_id=app.current_actor_id()
 AND p.verification_status='VERIFIED' AND ca.start_at<=now() AND (ca.end_at IS NULL OR ca.end_at>now()))
$$;
CREATE OR REPLACE FUNCTION app.can_access_patient(p_patient uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT app.is_staff(ARRAY['OWNER','ADMIN','SECRETARY','BILLING']) OR app.can_access_clinical(p_patient)
 OR EXISTS(SELECT 1 FROM app.patients p WHERE p.tenant_id=app.current_tenant_id() AND p.id=p_patient AND p.portal_user_id=app.current_actor_id())
 OR EXISTS(SELECT 1 FROM app.guardian_patient_access g WHERE g.tenant_id=app.current_tenant_id() AND g.patient_id=p_patient AND g.guardian_user_id=app.current_actor_id()
 AND g.legal_verified_at IS NOT NULL AND NOT g.review_required AND g.valid_from<=now() AND (g.valid_until IS NULL OR g.valid_until>now()))
$$;
-- Patient policy avoids recursive self reads by using a function owned by a dedicated role later.
CREATE POLICY appointments_scope ON app.appointments AS RESTRICTIVE USING(app.can_access_patient(patient_id)) WITH CHECK(app.can_access_patient(patient_id));
CREATE POLICY patients_scope ON app.patients AS RESTRICTIVE
 USING(app.is_staff(ARRAY['OWNER','ADMIN','SECRETARY','BILLING']) OR app.can_access_clinical(id) OR portal_user_id=app.current_actor_id()
 OR EXISTS(SELECT 1 FROM app.guardian_patient_access g WHERE g.tenant_id=patients.tenant_id AND g.patient_id=patients.id AND g.guardian_user_id=app.current_actor_id() AND g.legal_verified_at IS NOT NULL AND NOT g.review_required AND (g.valid_until IS NULL OR g.valid_until>now())))
 WITH CHECK(app.is_staff(ARRAY['OWNER','ADMIN','SECRETARY','PSYCHOLOGIST']) OR portal_user_id=app.current_actor_id());
CREATE POLICY notes_author_draft ON app.clinical_notes AS RESTRICTIVE
 USING((status='SIGNED' OR author_user_id=app.current_actor_id()) AND EXISTS(
 SELECT 1 FROM app.care_assignments ca JOIN app.professionals p ON p.tenant_id=ca.tenant_id AND p.id=ca.professional_id
 WHERE ca.tenant_id=app.current_tenant_id() AND ca.patient_id=clinical_notes.patient_id AND p.user_id=app.current_actor_id()
 AND ca.start_at<=clinical_notes.occurred_at AND (ca.end_at IS NULL OR ca.end_at>now())))
 WITH CHECK(app.can_access_clinical(patient_id) AND author_user_id=app.current_actor_id());

CREATE OR REPLACE FUNCTION app.can_read_patient_asset(p_file uuid,p_patient uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT app.can_access_clinical(p_patient) OR EXISTS(
 SELECT 1 FROM app.documents d JOIN app.patients p ON p.tenant_id=d.tenant_id AND p.id=d.patient_id
 WHERE d.tenant_id=app.current_tenant_id() AND d.signed_file_asset_id=p_file AND d.patient_id=p_patient
 AND d.status='ISSUED' AND d.published_to_patient_at IS NOT NULL AND p.portal_user_id=app.current_actor_id())
$$;
CREATE OR REPLACE FUNCTION app.can_read_published_doc(p_patient uuid,p_published timestamptz) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT p_published IS NOT NULL AND EXISTS(SELECT 1 FROM app.patients p WHERE p.tenant_id=app.current_tenant_id() AND p.id=p_patient AND p.portal_user_id=app.current_actor_id())
$$;
DROP POLICY docs_select_scope ON app.documents;
CREATE POLICY docs_select_scope ON app.documents AS RESTRICTIVE FOR SELECT
 USING(app.can_access_clinical(patient_id) OR (status='ISSUED' AND app.can_read_published_doc(patient_id,published_to_patient_at)));

CREATE POLICY public_professional_catalog ON app.professionals FOR SELECT
 USING(app.current_actor_id() IS NULL AND public_profile_enabled AND verification_status='VERIFIED');

CREATE OR REPLACE FUNCTION app.ensure_user(p_sub text) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=app,pg_temp AS $$
DECLARE uid uuid;
BEGIN
 INSERT INTO app.auth_users(oidc_sub) VALUES(p_sub) ON CONFLICT(oidc_sub) DO NOTHING;
 SELECT id INTO uid FROM app.auth_users WHERE oidc_sub=p_sub AND disabled_at IS NULL;
 IF uid IS NULL THEN RAISE EXCEPTION 'inactive identity'; END IF;
 RETURN uid;
END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA app FROM PUBLIC;
GRANT USAGE ON SCHEMA app TO conhecerse_runtime;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA app TO conhecerse_runtime;
REVOKE ALL ON app.auth_users FROM conhecerse_runtime;
REVOKE INSERT,UPDATE ON app.integration_routes FROM conhecerse_runtime;
REVOKE UPDATE ON app.audit_events,app.ledger_entries,app.appointment_history,app.consents FROM conhecerse_runtime;
GRANT EXECUTE ON FUNCTION app.ensure_user(text),app.current_tenant_id(),app.current_actor_id(),app.is_staff(text[]),app.can_access_clinical(uuid),app.can_access_patient(uuid),app.can_read_patient_asset(uuid,uuid),app.can_read_published_doc(uuid,timestamptz) TO conhecerse_runtime;
GRANT USAGE ON SCHEMA bff TO conhecerse_web;
GRANT SELECT,INSERT,UPDATE,DELETE ON bff.sessions TO conhecerse_web;

CREATE TABLE app.release_approvals (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), gate_code text NOT NULL,
 evidence_reference text NOT NULL, approved_by uuid NOT NULL REFERENCES app.auth_users(id), approved_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,gate_code)
);
ALTER TABLE app.release_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.release_approvals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.release_approvals USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id());
GRANT SELECT,INSERT ON app.release_approvals TO conhecerse_runtime;
CREATE TRIGGER tg_approvals_append_only BEFORE UPDATE OR DELETE ON app.release_approvals FOR EACH ROW EXECUTE FUNCTION app.append_only();
