ALTER TABLE app.communication_sends ADD COLUMN delivery_state text NOT NULL DEFAULT 'SENT' CHECK(delivery_state IN('SENT','DELIVERED','FAILED','SUPPRESSED'));
CREATE INDEX ix_communication_message ON app.communication_sends(tenant_id,provider_message_ref);
CREATE INDEX ix_communication_event_message ON app.communication_events(tenant_id,message_ref);
