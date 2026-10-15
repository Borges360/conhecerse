ALTER TABLE app.payments ADD COLUMN checkout_url text;
ALTER TABLE app.payments ADD COLUMN failure_code text;
ALTER TABLE app.payments ADD COLUMN recorded_by uuid REFERENCES app.auth_users(id);
CREATE POLICY index_insert ON app.user_tenant_index FOR INSERT WITH CHECK(true);
-- Context below is set only after a verified token and verified OIDC email match.
DROP POLICY patients_scope ON app.patients;
CREATE POLICY patients_scope ON app.patients AS RESTRICTIVE
 USING(app.is_staff(ARRAY['OWNER','ADMIN','SECRETARY','BILLING']) OR app.can_access_clinical(id) OR portal_user_id=app.current_actor_id()
 OR id=NULLIF(current_setting('app.invitation_patient_id',true),'')::uuid
 OR EXISTS(SELECT 1 FROM app.guardian_patient_access g WHERE g.tenant_id=patients.tenant_id AND g.patient_id=patients.id AND g.guardian_user_id=app.current_actor_id() AND g.legal_verified_at IS NOT NULL AND NOT g.review_required AND (g.valid_until IS NULL OR g.valid_until>now())))
 WITH CHECK(app.is_staff(ARRAY['OWNER','ADMIN','SECRETARY','PSYCHOLOGIST']) OR portal_user_id=app.current_actor_id());
DROP POLICY appointments_scope ON app.appointments;
CREATE POLICY appointments_scope ON app.appointments AS RESTRICTIVE
 USING(app.can_access_patient(patient_id) OR id=NULLIF(current_setting('app.action_appointment_id',true),'')::uuid)
 WITH CHECK(app.can_access_patient(patient_id) OR id=NULLIF(current_setting('app.action_appointment_id',true),'')::uuid);
