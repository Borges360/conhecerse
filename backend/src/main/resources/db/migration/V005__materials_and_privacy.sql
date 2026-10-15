ALTER TABLE app.content_entries ADD COLUMN file_asset_id uuid;
ALTER TABLE app.content_entries ADD CONSTRAINT content_asset_fk FOREIGN KEY(tenant_id,file_asset_id) REFERENCES app.file_assets(tenant_id,id);
ALTER TABLE app.organization_memberships ADD COLUMN service_account boolean NOT NULL DEFAULT false;
ALTER TABLE app.organizations ADD COLUMN worker_user_id uuid REFERENCES app.auth_users(id);
CREATE POLICY docs_draft_author ON app.documents AS RESTRICTIVE
 USING(status NOT IN('DRAFT','PENDING_SIGNATURE') OR author_user_id=app.current_actor_id())
 WITH CHECK(author_user_id=app.current_actor_id());
CREATE POLICY invoices_scope ON app.invoices AS RESTRICTIVE
 USING(app.is_staff(ARRAY['OWNER','ADMIN','BILLING']) OR EXISTS(
 SELECT 1 FROM app.invoice_payers ip JOIN app.patients p ON p.tenant_id=ip.tenant_id AND p.id=ip.patient_id
 WHERE ip.tenant_id=invoices.tenant_id AND ip.id=invoices.payer_id AND p.portal_user_id=app.current_actor_id()))
 WITH CHECK(app.is_staff(ARRAY['OWNER','ADMIN','BILLING']));
