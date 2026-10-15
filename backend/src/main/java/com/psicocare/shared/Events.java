package com.psicocare.shared;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Events {
    private final Store db;

    public Events(Store db) {
        this.db = db;
    }

    public void emit(Actor a, String type, String aggregate, UUID id, int version) {
        db.exec(
                "insert into"
                    + " app.outbox_events(tenant_id,event_type,event_version,aggregate_type,aggregate_id,aggregate_version,correlation_id,payload)"
                    + " values(?,?,1,?,?,?,?,?::jsonb)",
                a.tenant(),
                type,
                aggregate,
                id,
                version,
                a.requestId(),
                Json.object(aggregate + "Id", id).toString());
        audit(a, type, aggregate, id);
    }

    public void emitForPatient(
            Actor a, String type, String aggregate, UUID id, int version, UUID patient) {
        db.exec(
                "insert into"
                    + " app.outbox_events(tenant_id,event_type,event_version,aggregate_type,aggregate_id,aggregate_version,correlation_id,payload)"
                    + " values(?,?,1,?,?,?,?,?::jsonb)",
                a.tenant(),
                type,
                aggregate,
                id,
                version,
                a.requestId(),
                Json.object(aggregate + "Id", id, "patientId", patient).toString());
        audit(a, type, aggregate, id);
    }

    public void audit(Actor a, String action, String entity, UUID id) {
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?::text,1))",
                Object.class,
                a.tenant());
        var previous =
                db.optional(
                                "select event_sha256 from app.audit_events where tenant_id=? order"
                                        + " by occurred_at desc,id desc limit 1",
                                a.tenant())
                        .map(r -> (byte[]) r.get("event_sha256"))
                        .orElse(new byte[0]);
        var data =
                Json.object(
                        "action",
                        action,
                        "entity",
                        entity,
                        "id",
                        id,
                        "actor",
                        a.user(),
                        "requestId",
                        a.requestId());
        db.exec(
                "insert into"
                    + " app.audit_events(tenant_id,actor_user_id,action_code,entity_type,entity_id,correlation_id,success,previous_hash,event_sha256)"
                    + " values(?,?,?,?,?,?,true,?,?)",
                a.tenant(),
                a.user(),
                action,
                entity,
                id,
                a.requestId(),
                previous,
                Json.hash(HexFormat.of().formatHex(previous) + Json.canonical(data)));
    }
}
