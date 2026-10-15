-- Expand-only. No approval, subscription price or clinical policy is seeded.
ALTER TABLE app.organizations ADD COLUMN registration_cipher bytea;
ALTER TABLE app.professionals ADD COLUMN public_attributes jsonb NOT NULL DEFAULT '{}';
ALTER TABLE app.professionals ADD COLUMN specialties_reviewed jsonb NOT NULL DEFAULT '[]';
ALTER TABLE app.patients ADD COLUMN address_cipher bytea;
ALTER TABLE app.patients ADD COLUMN emergency_contact_cipher bytea;
ALTER TABLE app.privacy_requests ADD COLUMN due_at timestamptz NOT NULL DEFAULT now()+interval '15 days';
ALTER TABLE app.privacy_requests ADD COLUMN version int NOT NULL DEFAULT 1;

CREATE TABLE app.remote_protocols (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 version int NOT NULL, payload_cipher bytea NOT NULL, submitted_by uuid NOT NULL REFERENCES app.auth_users(id),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED')),
 reviewed_by uuid REFERENCES app.auth_users(id), reviewed_at timestamptz, evidence_cipher bytea,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,patient_id,version),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.guardian_clinical_reviews (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), guardian_access_id uuid NOT NULL,
 patient_id uuid NOT NULL, legal_reviewer uuid NOT NULL REFERENCES app.auth_users(id),
 clinical_reviewer uuid REFERENCES app.auth_users(id), evidence_cipher bytea NOT NULL,
 expires_at timestamptz NOT NULL, revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,guardian_access_id) REFERENCES app.guardian_patient_access(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.record_exports (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), request_id uuid NOT NULL,
 patient_id uuid NOT NULL, recipient_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 file_id uuid NOT NULL, prepared_by uuid NOT NULL REFERENCES app.auth_users(id),
 reviewed_by uuid REFERENCES app.auth_users(id), review_evidence_cipher bytea,
 status text NOT NULL DEFAULT 'PREPARED' CHECK(status IN('PREPARED','RELEASED','DELIVERED','REVOKED')),
 expires_at timestamptz, delivered_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES app.privacy_requests(tenant_id,id),
 FOREIGN KEY(tenant_id,file_id) REFERENCES app.file_assets(tenant_id,id)
);
CREATE UNIQUE INDEX uq_live_record_export ON app.record_exports(tenant_id,request_id) WHERE status<>'REVOKED';
CREATE TABLE app.break_glass_requests (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
 requested_by uuid NOT NULL REFERENCES app.auth_users(id), reason_cipher bytea NOT NULL,
 scope text NOT NULL CHECK(scope='SIGNED_NOTES_READ'), status text NOT NULL DEFAULT 'PENDING'
 CHECK(status IN('PENDING','ACTIVE','REVOKED','DENIED')), duration_minutes int NOT NULL CHECK(duration_minutes BETWEEN 1 AND 15),
 request_expires_at timestamptz NOT NULL DEFAULT now()+interval '30 minutes', expires_at timestamptz,
 revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id)
);
CREATE TABLE app.break_glass_approvals (
 tenant_id uuid NOT NULL, request_id uuid NOT NULL, reviewer_user_id uuid NOT NULL REFERENCES app.auth_users(id),
 review_role text NOT NULL CHECK(review_role IN('SECURITY','CLINICAL')), evidence_cipher bytea NOT NULL,
 approved_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,request_id,review_role),
 UNIQUE(tenant_id,request_id,reviewer_user_id), FOREIGN KEY(tenant_id,request_id) REFERENCES app.break_glass_requests(tenant_id,id)
);
CREATE TABLE app.content_read_totals (
 tenant_id uuid NOT NULL, content_id uuid NOT NULL, day date NOT NULL DEFAULT current_date,
 read_count bigint NOT NULL DEFAULT 0 CHECK(read_count>=0), PRIMARY KEY(tenant_id,content_id,day),
 FOREIGN KEY(tenant_id,content_id) REFERENCES app.content_entries(tenant_id,id)
);
CREATE TABLE app.document_signature_checks (
 tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), document_id uuid NOT NULL, file_id uuid NOT NULL,
 checked_by uuid NOT NULL REFERENCES app.auth_users(id), checked_at timestamptz NOT NULL DEFAULT now(),
 result jsonb NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES app.documents(tenant_id,id), FOREIGN KEY(tenant_id,file_id) REFERENCES app.file_assets(tenant_id,id)
);
CREATE TABLE app.communication_events (
 tenant_id uuid NOT NULL, provider text NOT NULL, event_id text NOT NULL, message_ref text NOT NULL,
 status text NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,provider,event_id)
);
CREATE TABLE app.communication_suppressions (
 tenant_id uuid NOT NULL, channel text NOT NULL, recipient_hmac bytea NOT NULL, reason_code text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,channel,recipient_hmac)
);
CREATE TABLE app.communication_sends (
 tenant_id uuid NOT NULL, request_id uuid NOT NULL, channel text NOT NULL, recipient_hmac bytea NOT NULL,
 provider_message_ref text, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,request_id)
);
ALTER TABLE app.platform_subscriptions ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE app.platform_subscriptions ADD COLUMN amount_cents bigint CHECK(amount_cents>0);
ALTER TABLE app.platform_subscriptions ADD COLUMN customer_cipher bytea;
ALTER TABLE app.platform_subscriptions ADD COLUMN cancel_requested_at timestamptz;
ALTER TABLE app.platform_subscriptions ADD COLUMN last_reconciled_at timestamptz;
ALTER TABLE app.platform_subscriptions ADD COLUMN provider_state text;
ALTER TABLE app.platform_subscriptions ADD COLUMN terms_version text;
ALTER TABLE app.platform_subscriptions ADD COLUMN request_state text NOT NULL DEFAULT 'PENDING' CHECK(request_state IN('PENDING','UNKNOWN','CONFIRMED'));
CREATE UNIQUE INDEX uq_platform_subscription_active ON app.platform_subscriptions(tenant_id) WHERE status<>'CANCELLED';
CREATE TABLE app.platform_subscription_charges (
 tenant_id uuid NOT NULL, subscription_id uuid NOT NULL, provider_payment_ref text NOT NULL,
 due_on date NOT NULL, amount_cents bigint NOT NULL, status text NOT NULL, checkout_url text,
 PRIMARY KEY(tenant_id,provider_payment_ref), FOREIGN KEY(tenant_id,subscription_id) REFERENCES app.platform_subscriptions(tenant_id,id)
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['remote_protocols','guardian_clinical_reviews','record_exports','break_glass_requests',
 'break_glass_approvals','content_read_totals','document_signature_checks','communication_events','communication_suppressions',
 'communication_sends','platform_subscription_charges'] LOOP
 EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY tenant_isolation ON app.%I USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id())',t);
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON app.%I TO conhecerse_runtime',t);
 END LOOP;
END $$;
CREATE POLICY remote_read ON app.remote_protocols AS RESTRICTIVE FOR SELECT USING(app.can_access_clinical(patient_id) OR EXISTS(SELECT 1 FROM app.patients p WHERE p.tenant_id=remote_protocols.tenant_id AND p.id=patient_id AND p.portal_user_id=app.current_actor_id()));
CREATE POLICY remote_insert ON app.remote_protocols AS RESTRICTIVE FOR INSERT WITH CHECK(app.can_access_clinical(patient_id) OR EXISTS(SELECT 1 FROM app.patients p WHERE p.tenant_id=remote_protocols.tenant_id AND p.id=patient_id AND p.portal_user_id=app.current_actor_id()));
CREATE POLICY remote_update ON app.remote_protocols AS RESTRICTIVE FOR UPDATE USING(app.can_access_clinical(patient_id)) WITH CHECK(app.can_access_clinical(patient_id));
CREATE POLICY export_read ON app.record_exports AS RESTRICTIVE FOR SELECT USING(app.can_access_clinical(patient_id) OR recipient_user_id=app.current_actor_id());
CREATE POLICY export_insert ON app.record_exports AS RESTRICTIVE FOR INSERT WITH CHECK(app.can_access_clinical(patient_id));
CREATE POLICY export_update ON app.record_exports AS RESTRICTIVE FOR UPDATE USING(app.can_access_clinical(patient_id) OR recipient_user_id=app.current_actor_id()) WITH CHECK(app.can_access_clinical(patient_id) OR recipient_user_id=app.current_actor_id());
CREATE FUNCTION app.has_emergency_read(p_patient uuid) RETURNS boolean LANGUAGE sql STABLE SET search_path=app,pg_temp AS $$
 SELECT app.is_staff(ARRAY['OWNER','ADMIN','PSYCHOLOGIST']) AND EXISTS(
 SELECT 1 FROM app.break_glass_requests r WHERE r.tenant_id=app.current_tenant_id() AND r.patient_id=p_patient
 AND r.requested_by=app.current_actor_id() AND r.status='ACTIVE' AND r.expires_at>now() AND r.revoked_at IS NULL
 AND (SELECT count(*) FROM app.break_glass_approvals a JOIN app.organization_memberships m ON m.tenant_id=a.tenant_id AND m.user_id=a.reviewer_user_id AND m.status='ACTIVE'
 WHERE a.tenant_id=r.tenant_id AND a.request_id=r.id AND a.reviewer_user_id<>r.requested_by
 AND ((a.review_role='SECURITY' AND m.roles::text[] && ARRAY['OWNER','ADMIN']) OR (a.review_role='CLINICAL' AND 'PSYCHOLOGIST'=ANY(m.roles)
 AND EXISTS(SELECT 1 FROM app.care_assignments ca JOIN app.professionals p ON p.tenant_id=ca.tenant_id AND p.id=ca.professional_id WHERE ca.tenant_id=r.tenant_id AND ca.patient_id=r.patient_id AND p.user_id=a.reviewer_user_id AND p.verification_status='VERIFIED' AND ca.start_at<=now() AND (ca.end_at IS NULL OR ca.end_at>now())))))=2)
$$;
REVOKE ALL ON FUNCTION app.has_emergency_read(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.has_emergency_read(uuid) TO conhecerse_runtime;
DROP POLICY notes_care_scope ON app.clinical_notes;
CREATE POLICY notes_care_scope ON app.clinical_notes AS RESTRICTIVE USING(app.can_access_clinical(patient_id) OR (status='SIGNED' AND app.has_emergency_read(patient_id))) WITH CHECK(app.can_access_clinical(patient_id));
DROP POLICY notes_author_draft ON app.clinical_notes;
CREATE POLICY notes_author_draft ON app.clinical_notes AS RESTRICTIVE USING(
 (status='SIGNED' AND app.has_emergency_read(patient_id)) OR
 ((status='SIGNED' OR author_user_id=app.current_actor_id()) AND EXISTS(SELECT 1 FROM app.care_assignments ca JOIN app.professionals p ON p.tenant_id=ca.tenant_id AND p.id=ca.professional_id WHERE ca.tenant_id=app.current_tenant_id() AND ca.patient_id=clinical_notes.patient_id AND p.user_id=app.current_actor_id() AND ca.start_at<=clinical_notes.occurred_at AND (ca.end_at IS NULL OR ca.end_at>now()))))
 WITH CHECK(app.can_access_clinical(patient_id) AND author_user_id=app.current_actor_id());
-- Export authorization never grants direct access to notes or test material.
CREATE OR REPLACE FUNCTION app.can_read_patient_asset(p_file uuid,p_patient uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=app,pg_temp AS $$
 SELECT app.can_access_clinical(p_patient) OR EXISTS(SELECT 1 FROM app.documents d JOIN app.patients p ON p.tenant_id=d.tenant_id AND p.id=d.patient_id WHERE d.tenant_id=app.current_tenant_id() AND d.signed_file_asset_id=p_file AND d.patient_id=p_patient AND d.status='ISSUED' AND d.published_to_patient_at IS NOT NULL AND p.portal_user_id=app.current_actor_id())
 OR EXISTS(SELECT 1 FROM app.record_exports e JOIN app.privacy_requests r ON r.tenant_id=e.tenant_id AND r.id=e.request_id WHERE e.tenant_id=app.current_tenant_id() AND e.file_id=p_file AND e.patient_id=p_patient AND e.recipient_user_id=app.current_actor_id() AND e.status IN('RELEASED','DELIVERED') AND e.expires_at>now() AND r.status NOT IN('DENIED_WITH_REASON') AND (
 EXISTS(SELECT 1 FROM app.patients p WHERE p.tenant_id=e.tenant_id AND p.id=e.patient_id AND p.portal_user_id=e.recipient_user_id) OR
 EXISTS(SELECT 1 FROM app.guardian_patient_access g JOIN app.guardian_clinical_reviews gr ON gr.tenant_id=g.tenant_id AND gr.guardian_access_id=g.id WHERE g.tenant_id=e.tenant_id AND g.patient_id=e.patient_id AND g.guardian_user_id=e.recipient_user_id AND g.legal_verified_at IS NOT NULL AND NOT g.review_required AND g.valid_from<=now() AND (g.valid_until IS NULL OR g.valid_until>now()) AND gr.clinical_reviewer IS NOT NULL AND gr.expires_at>now() AND gr.revoked_at IS NULL)))
$$;
CREATE TRIGGER tg_emergency_approvals_immutable BEFORE UPDATE OR DELETE ON app.break_glass_approvals FOR EACH ROW EXECUTE FUNCTION app.append_only();
CREATE TRIGGER tg_signature_checks_immutable BEFORE UPDATE OR DELETE ON app.document_signature_checks FOR EACH ROW EXECUTE FUNCTION app.append_only();
CREATE TRIGGER tg_communication_events_immutable BEFORE UPDATE OR DELETE ON app.communication_events FOR EACH ROW EXECUTE FUNCTION app.append_only();
REVOKE UPDATE ON app.break_glass_approvals,app.document_signature_checks,app.communication_events FROM conhecerse_runtime;
