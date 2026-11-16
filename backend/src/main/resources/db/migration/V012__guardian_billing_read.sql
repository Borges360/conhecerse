DROP POLICY invoices_scope ON app.invoices;
CREATE POLICY invoices_scope ON app.invoices AS RESTRICTIVE
 USING(app.is_staff(ARRAY['OWNER','ADMIN','BILLING']) OR EXISTS(
 SELECT 1 FROM app.invoice_payers ip JOIN app.patients p ON p.tenant_id=ip.tenant_id AND p.id=ip.patient_id
 WHERE ip.tenant_id=invoices.tenant_id AND ip.id=invoices.payer_id AND p.portal_user_id=app.current_actor_id())
 OR EXISTS(
 SELECT 1 FROM app.invoice_payers ip JOIN app.guardian_patient_access g
   ON g.tenant_id=ip.tenant_id AND g.patient_id=ip.patient_id
 WHERE ip.tenant_id=invoices.tenant_id AND ip.id=invoices.payer_id
   AND g.guardian_user_id=app.current_actor_id() AND g.legal_verified_at IS NOT NULL
   AND NOT g.review_required AND g.valid_from<=now()
   AND (g.valid_until IS NULL OR g.valid_until>now())
   AND 'BILLING'=ANY(g.access_scope)))
 WITH CHECK(app.is_staff(ARRAY['OWNER','ADMIN','BILLING']));
