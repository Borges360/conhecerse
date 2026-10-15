-- Read-only emergency grants and reviewed exports cannot be promoted by tenant-wide DML.
CREATE POLICY emergency_insert ON app.break_glass_requests AS RESTRICTIVE FOR INSERT WITH CHECK(
 requested_by=app.current_actor_id() AND app.is_staff(ARRAY['OWNER','ADMIN','PSYCHOLOGIST'])
 AND status='PENDING' AND expires_at IS NULL AND revoked_at IS NULL AND request_expires_at<=now()+interval '30 minutes');
CREATE POLICY emergency_update ON app.break_glass_requests AS RESTRICTIVE FOR UPDATE USING(
 app.is_staff(ARRAY['OWNER','ADMIN']) OR requested_by=app.current_actor_id() OR app.can_access_clinical(patient_id)) WITH CHECK(
 status<>'ACTIVE' OR (expires_at<=now()+interval '15 minutes' AND
 (SELECT count(*) FROM app.break_glass_approvals a WHERE a.tenant_id=break_glass_requests.tenant_id AND a.request_id=break_glass_requests.id)=2));
CREATE POLICY emergency_approval_insert ON app.break_glass_approvals AS RESTRICTIVE FOR INSERT WITH CHECK(
 reviewer_user_id=app.current_actor_id() AND approved_at<=now() AND approved_at>=now()-interval '1 minute'
 AND EXISTS(SELECT 1 FROM app.break_glass_requests r WHERE r.tenant_id=break_glass_approvals.tenant_id AND r.id=request_id
 AND r.status='PENDING' AND r.request_expires_at>now() AND r.requested_by<>app.current_actor_id()
 AND ((review_role='SECURITY' AND app.is_staff(ARRAY['OWNER','ADMIN'])) OR (review_role='CLINICAL' AND app.can_access_clinical(r.patient_id)))));
CREATE POLICY guardian_review_read ON app.guardian_clinical_reviews AS RESTRICTIVE FOR SELECT USING(
 app.is_staff(ARRAY['OWNER','ADMIN']) OR app.can_access_clinical(patient_id) OR EXISTS(SELECT 1 FROM app.guardian_patient_access g WHERE g.tenant_id=guardian_clinical_reviews.tenant_id AND g.id=guardian_access_id AND g.guardian_user_id=app.current_actor_id()));
CREATE POLICY guardian_review_insert ON app.guardian_clinical_reviews AS RESTRICTIVE FOR INSERT WITH CHECK(
 app.is_staff(ARRAY['OWNER','ADMIN']) AND legal_reviewer=app.current_actor_id() AND clinical_reviewer IS NULL AND expires_at<=now()+interval '90 days');
CREATE POLICY guardian_review_update ON app.guardian_clinical_reviews AS RESTRICTIVE FOR UPDATE USING(
 app.is_staff(ARRAY['OWNER','ADMIN']) OR app.can_access_clinical(patient_id)) WITH CHECK(
 revoked_at IS NOT NULL OR (app.can_access_clinical(patient_id) AND clinical_reviewer=app.current_actor_id() AND clinical_reviewer<>legal_reviewer AND expires_at<=now()+interval '90 days'));
CREATE POLICY signature_checks_read ON app.document_signature_checks AS RESTRICTIVE FOR SELECT USING(
 EXISTS(SELECT 1 FROM app.documents d WHERE d.tenant_id=document_signature_checks.tenant_id AND d.id=document_id AND app.can_access_clinical(d.patient_id)));
CREATE POLICY signature_checks_insert ON app.document_signature_checks AS RESTRICTIVE FOR INSERT WITH CHECK(
 checked_by=app.current_actor_id() AND EXISTS(SELECT 1 FROM app.documents d WHERE d.tenant_id=document_signature_checks.tenant_id AND d.id=document_id AND d.author_user_id=app.current_actor_id() AND app.can_access_clinical(d.patient_id)));
CREATE POLICY privacy_read ON app.privacy_requests AS RESTRICTIVE FOR SELECT USING(
 requested_by=app.current_actor_id() OR app.is_staff(ARRAY['OWNER','ADMIN']) OR app.can_access_clinical(patient_id));
CREATE POLICY privacy_insert ON app.privacy_requests AS RESTRICTIVE FOR INSERT WITH CHECK(
 requested_by=app.current_actor_id() AND (app.is_staff(ARRAY['OWNER','ADMIN']) OR app.can_access_patient(patient_id)));
CREATE POLICY privacy_update ON app.privacy_requests AS RESTRICTIVE FOR UPDATE USING(
 app.is_staff(ARRAY['OWNER','ADMIN']) OR app.can_access_clinical(patient_id) OR requested_by=app.current_actor_id()) WITH CHECK(
 status<>'FULFILLED' OR request_type NOT IN('ACCESS','PORTABILITY') OR EXISTS(SELECT 1 FROM app.record_exports e WHERE e.tenant_id=privacy_requests.tenant_id AND e.request_id=privacy_requests.id AND e.status='DELIVERED' AND e.delivered_at IS NOT NULL));
-- Recipients cannot change a prepared package into a released package.
CREATE FUNCTION app.guard_export_transition() RETURNS trigger LANGUAGE plpgsql SET search_path=app,pg_temp AS $$
BEGIN
 IF OLD.status='PREPARED' AND NEW.status<>'REVOKED' AND NEW.status<>'PREPARED' AND NOT app.can_access_clinical(OLD.patient_id) THEN RAISE EXCEPTION 'clinical review required'; END IF;
 IF (to_jsonb(NEW)-'status'-'expires_at'-'delivered_at'-'reviewed_by'-'review_evidence_cipher') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'expires_at'-'delivered_at'-'reviewed_by'-'review_evidence_cipher') THEN RAISE EXCEPTION 'export content immutable'; END IF;
 IF OLD.status IN('RELEASED','DELIVERED','REVOKED') AND (NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.review_evidence_cipher IS DISTINCT FROM OLD.review_evidence_cipher) THEN RAISE EXCEPTION 'export review immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER tg_export_transition BEFORE UPDATE ON app.record_exports FOR EACH ROW EXECUTE FUNCTION app.guard_export_transition();
REVOKE ALL ON FUNCTION app.guard_export_transition() FROM PUBLIC;
