CREATE TABLE app.user_tenant_index(tenant_id uuid NOT NULL REFERENCES app.organizations(id),user_id uuid NOT NULL REFERENCES app.auth_users(id),PRIMARY KEY(tenant_id,user_id));
CREATE FUNCTION app.sync_user_tenant() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=app,pg_temp AS $$
BEGIN
 IF TG_TABLE_NAME='patients' THEN
  IF NEW.portal_user_id IS NOT NULL THEN INSERT INTO app.user_tenant_index VALUES(NEW.tenant_id,NEW.portal_user_id) ON CONFLICT DO NOTHING; END IF;
 ELSIF TG_TABLE_NAME='guardian_patient_access' THEN
  INSERT INTO app.user_tenant_index VALUES(NEW.tenant_id,NEW.guardian_user_id) ON CONFLICT DO NOTHING;
 ELSE INSERT INTO app.user_tenant_index VALUES(NEW.tenant_id,NEW.user_id) ON CONFLICT DO NOTHING;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER tg_membership_index AFTER INSERT OR UPDATE ON app.organization_memberships FOR EACH ROW EXECUTE FUNCTION app.sync_user_tenant();
CREATE TRIGGER tg_portal_index AFTER INSERT OR UPDATE ON app.patients FOR EACH ROW EXECUTE FUNCTION app.sync_user_tenant();
CREATE TRIGGER tg_guardian_index AFTER INSERT OR UPDATE ON app.guardian_patient_access FOR EACH ROW EXECUTE FUNCTION app.sync_user_tenant();
ALTER TABLE app.user_tenant_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.user_tenant_index FORCE ROW LEVEL SECURITY;
CREATE POLICY actor_index ON app.user_tenant_index USING(user_id=app.current_actor_id());
GRANT SELECT ON app.user_tenant_index TO conhecerse_runtime;
-- Trigger functions have no runtime EXECUTE grant; table triggers execute under the migration owner.

CREATE TABLE app.bootstrap_idempotency(actor_user_id uuid NOT NULL REFERENCES app.auth_users(id),idem_key uuid NOT NULL,body_hash bytea NOT NULL,organization_id uuid NOT NULL REFERENCES app.organizations(id),PRIMARY KEY(actor_user_id,idem_key));
ALTER TABLE app.bootstrap_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.bootstrap_idempotency FORCE ROW LEVEL SECURITY;
CREATE POLICY actor_bootstrap ON app.bootstrap_idempotency USING(actor_user_id=app.current_actor_id()) WITH CHECK(actor_user_id=app.current_actor_id());
GRANT SELECT,INSERT ON app.bootstrap_idempotency TO conhecerse_runtime;
ALTER TABLE app.action_tokens ADD COLUMN membership_id uuid;
ALTER TABLE app.action_tokens ADD COLUMN email_lookup_hmac bytea;
ALTER TABLE app.action_tokens ADD COLUMN delivery_cipher bytea;
CREATE TABLE app.action_routes(token_hash bytea PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES app.organizations(id));
CREATE FUNCTION app.sync_action_route() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=app,pg_temp AS $$
BEGIN INSERT INTO app.action_routes VALUES(NEW.token_hash,NEW.tenant_id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;
CREATE TRIGGER tg_action_route AFTER INSERT ON app.action_tokens FOR EACH ROW EXECUTE FUNCTION app.sync_action_route();
GRANT SELECT ON app.action_routes TO conhecerse_runtime;

CREATE TABLE app.public_idempotency(tenant_id uuid NOT NULL, fingerprint bytea NOT NULL, route_key text NOT NULL,idem_key uuid NOT NULL,body_hash bytea NOT NULL,result_id uuid NOT NULL,expires_at timestamptz NOT NULL DEFAULT now()+interval '72 hours',PRIMARY KEY(tenant_id,fingerprint,route_key,idem_key));
ALTER TABLE app.public_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.public_idempotency FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.public_idempotency USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id());
GRANT SELECT,INSERT,UPDATE ON app.public_idempotency TO conhecerse_runtime;
CREATE TABLE app.retention_holds(tenant_id uuid NOT NULL,patient_id uuid NOT NULL,reason_code text NOT NULL,created_by uuid NOT NULL REFERENCES app.auth_users(id),created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,patient_id),FOREIGN KEY(tenant_id,patient_id) REFERENCES app.patients(tenant_id,id));
ALTER TABLE app.retention_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.retention_holds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.retention_holds USING(tenant_id=app.current_tenant_id()) WITH CHECK(tenant_id=app.current_tenant_id());
GRANT SELECT,INSERT ON app.retention_holds TO conhecerse_runtime;
