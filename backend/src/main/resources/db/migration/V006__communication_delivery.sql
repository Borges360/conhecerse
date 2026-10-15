ALTER TABLE app.public_booking_requests ADD COLUMN patient_id uuid;
ALTER TABLE app.public_booking_requests ADD COLUMN appointment_id uuid;
ALTER TABLE app.public_booking_requests ADD CONSTRAINT booking_patient_fk FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id);
ALTER TABLE app.public_booking_requests ADD CONSTRAINT booking_appointment_fk FOREIGN KEY(tenant_id,appointment_id) REFERENCES app.appointments(tenant_id,id);
ALTER TABLE app.organization_memberships ADD COLUMN email_cipher bytea;
ALTER TABLE app.notifications ADD COLUMN action_token_id uuid;
ALTER TABLE app.notifications ADD CONSTRAINT notification_action_token_fk FOREIGN KEY(tenant_id,action_token_id) REFERENCES app.action_tokens(tenant_id,id);
ALTER TABLE app.care_assignments ADD COLUMN transfer_evidence_cipher bytea;
ALTER TABLE app.guardian_patient_access ADD COLUMN evidence_cipher bytea;
