ALTER TABLE app.file_assets DROP CONSTRAINT file_assets_classification_check;
ALTER TABLE app.file_assets ADD CONSTRAINT file_assets_classification_check CHECK(classification IN('PUBLIC','INTERNAL','PERSONAL','SENSITIVE_CLINICAL','FINANCIAL','RESTRICTED_TEST'));
DROP POLICY clinical_assets_read_scope ON app.file_assets;
DROP POLICY clinical_assets_insert_scope ON app.file_assets;
DROP POLICY clinical_assets_update_scope ON app.file_assets;
CREATE POLICY clinical_assets_read_scope ON app.file_assets AS RESTRICTIVE FOR SELECT USING(
  classification NOT IN('SENSITIVE_CLINICAL','RESTRICTED_TEST')
  OR classification='RESTRICTED_TEST' AND app.can_access_clinical(owner_patient_id)
  OR classification='SENSITIVE_CLINICAL' AND app.can_read_patient_asset(id,owner_patient_id));
CREATE POLICY clinical_assets_insert_scope ON app.file_assets AS RESTRICTIVE FOR INSERT WITH CHECK(
  classification NOT IN('SENSITIVE_CLINICAL','RESTRICTED_TEST') OR app.can_access_clinical(owner_patient_id));
CREATE POLICY clinical_assets_update_scope ON app.file_assets AS RESTRICTIVE FOR UPDATE USING(
  classification NOT IN('SENSITIVE_CLINICAL','RESTRICTED_TEST') OR app.can_access_clinical(owner_patient_id)) WITH CHECK(
  classification NOT IN('SENSITIVE_CLINICAL','RESTRICTED_TEST') OR app.can_access_clinical(owner_patient_id));
ALTER TABLE app.restricted_test_materials ADD COLUMN file_asset_id uuid;
ALTER TABLE app.restricted_test_materials ADD CONSTRAINT restricted_asset_fk FOREIGN KEY(tenant_id,file_asset_id) REFERENCES app.file_assets(tenant_id,id);
CREATE TABLE app.clinical_files(
  tenant_id uuid NOT NULL, id uuid NOT NULL DEFAULT gen_random_uuid(), patient_id uuid NOT NULL,
  file_asset_id uuid NOT NULL, added_by uuid NOT NULL REFERENCES app.auth_users(id),created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,patient_id,file_asset_id),
  FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id),
  FOREIGN KEY(tenant_id,file_asset_id) REFERENCES app.file_assets(tenant_id,id));
ALTER TABLE app.clinical_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.clinical_files FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.clinical_files USING(tenant_id=app.current_tenant_id() AND app.can_access_clinical(patient_id)) WITH CHECK(tenant_id=app.current_tenant_id() AND app.can_access_clinical(patient_id));
GRANT SELECT,INSERT ON app.clinical_files TO conhecerse_runtime;
CREATE TRIGGER tg_clinical_files_append_only BEFORE UPDATE OR DELETE ON app.clinical_files FOR EACH ROW EXECUTE FUNCTION app.append_only();
