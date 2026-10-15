package com.psicocare.clinical;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class ClinicalModule implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Crypto crypto;
    private final Events events;

    public ClinicalModule(Store db, Access access, Crypto crypto, Events events) {
        this.db = db;
        this.access = access;
        this.crypto = crypto;
        this.events = events;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Clinical");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        var b = c.body();
        UUID tenant = a.tenant();
        if (c.vars().containsKey("patientId")) {
            UUID patient = c.id("patientId");
            access.requireClinical(a, patient);
            if (c.isGet())
                return c.page(
                        db
                                .rows(
                                        "select * from app.clinical_notes where tenant_id=? and"
                                                + " patient_id=? order by occurred_at desc",
                                        tenant,
                                        patient)
                                .stream()
                                .map(r -> note(tenant, r))
                                .toList());
            Problem.require(patient.equals(uuid(b, "patientId")), 422, "PATIENT_MISMATCH");
            return create(a, b, null);
        }
        UUID nid = c.id("noteId");
        var r =
                db.one(
                        "select * from app.clinical_notes where tenant_id=? and id=?"
                                + (c.isGet() ? "" : " for update"),
                        tenant,
                        nid);
        access.requireClinical(a, id(r, "patient_id"));
        if (c.isGet()) return note(tenant, r);
        if (c.path().endsWith("/addenda")) {
            Problem.require(str(r, "status").equals("SIGNED"), 409, "INVALID_TRANSITION");
            Problem.require(
                    uuid(b, "patientId").equals(id(r, "patient_id")), 422, "PATIENT_MISMATCH");
            return create(a, b, nid);
        }
        Problem.require(str(r, "status").equals("DRAFT"), 409, "IMMUTABLE_RESOURCE");
        Problem.require(id(r, "author_user_id").equals(a.user()), 404, "RESOURCE_NOT_FOUND");
        c.version(r);
        var fields =
                object(
                        "narrative",
                        decrypt(tenant, r, "narrative"),
                        "plan",
                        decrypt(tenant, r, "plan"),
                        "referrals",
                        decrypt(tenant, r, "referrals"));
        int v = version(r) + 1;
        if (c.path().endsWith("/sign")) {
            a.recentMfa();
            Problem.require(!text(fields, "narrative").isBlank(), 422, "REQUIRED_FIELD_MISSING");
            var immutable =
                    object(
                            "id",
                            nid,
                            "patientId",
                            r.get("patient_id"),
                            "appointmentId",
                            r.get("appointment_id"),
                            "authorId",
                            r.get("author_user_id"),
                            "occurredAt",
                            r.get("occurred_at").toString(),
                            "noteType",
                            r.get("note_type"),
                            "revisionOfId",
                            r.get("revision_of_id"),
                            "fields",
                            fields);
            db.exec(
                    "update app.clinical_notes set"
                        + " narrative_cipher=?,plan_cipher=?,referrals_cipher=?,status='SIGNED',signed_at=now(),signed_by=?,content_sha256=?,version=?,updated_at=now()"
                        + " where tenant_id=? and id=?",
                    encrypt(tenant, nid, v, "narrative", text(fields, "narrative")),
                    encrypt(tenant, nid, v, "plan", text(fields, "plan")),
                    encrypt(tenant, nid, v, "referrals", text(fields, "referrals")),
                    a.user(),
                    hash(canonical(immutable)),
                    v,
                    tenant,
                    nid);
            events.emit(a, "ClinicalNoteSigned", "clinicalNote", nid, v);
        } else {
            b.properties().forEach(f -> fields.set(f.getKey(), f.getValue()));
            db.exec(
                    "update app.clinical_notes set"
                        + " narrative_cipher=?,plan_cipher=?,referrals_cipher=?,version=?,updated_at=now()"
                        + " where tenant_id=? and id=?",
                    encrypt(tenant, nid, v, "narrative", text(fields, "narrative")),
                    encrypt(tenant, nid, v, "plan", text(fields, "plan")),
                    encrypt(tenant, nid, v, "referrals", text(fields, "referrals")),
                    v,
                    tenant,
                    nid);
            events.audit(a, "ClinicalDraftSaved", "clinicalNote", nid);
        }
        return note(
                tenant,
                db.one("select * from app.clinical_notes where tenant_id=? and id=?", tenant, nid));
    }

    private JsonNode create(Actor a, JsonNode b, UUID revision) {
        UUID patient = uuid(b, "patientId"), id = UUID.randomUUID();
        access.requireClinical(a, patient);
        if (b.has("appointmentId"))
            Problem.require(
                    db.exists(
                            "select 1 from app.appointments where tenant_id=? and id=? and"
                                    + " patient_id=? and professional_id=?",
                            a.tenant(),
                            uuid(b, "appointmentId"),
                            patient,
                            access.professional(a)),
                    404,
                    "RESOURCE_NOT_FOUND");
        if (b.has("revisionOfId") && revision == null)
            throw new Problem(422, "USE_ADDENDUM_ENDPOINT");
        OffsetDateTime occurred = OffsetDateTime.parse(text(b, "occurredAt"));
        Problem.require(
                !occurred.isAfter(OffsetDateTime.now().plusMinutes(5)), 422, "INVALID_LOCAL_TIME");
        db.exec(
                "insert into"
                    + " app.clinical_notes(tenant_id,id,patient_id,appointment_id,author_user_id,note_type,occurred_at,narrative_cipher,plan_cipher,referrals_cipher,encryption_key_id,encryption_key_version,revision_of_id)"
                    + " values(?,?,?,?,?,?,?,?,?,?,'tenant-envelope',1,?)",
                a.tenant(),
                id,
                patient,
                b.has("appointmentId") ? uuid(b, "appointmentId") : null,
                a.user(),
                revision == null ? b.path("noteType").asText("EVOLUTION") : "ADDENDUM",
                occurred,
                encrypt(a.tenant(), id, 1, "narrative", text(b, "narrative")),
                encrypt(a.tenant(), id, 1, "plan", text(b, "plan")),
                encrypt(a.tenant(), id, 1, "referrals", text(b, "referrals")),
                revision);
        events.audit(a, "ClinicalDraftCreated", "clinicalNote", id);
        return note(
                a.tenant(),
                db.one(
                        "select * from app.clinical_notes where tenant_id=? and id=?",
                        a.tenant(),
                        id));
    }

    private byte[] encrypt(UUID tenant, UUID id, int version, String field, String value) {
        return crypto.encrypt(tenant, "clinical_notes." + field, id, version, value);
    }

    private String decrypt(UUID tenant, Map<String, Object> r, String field) {
        return crypto.decrypt(
                tenant,
                "clinical_notes." + field,
                id(r, "id"),
                version(r),
                (byte[]) r.get(field + "_cipher"));
    }

    private JsonNode note(UUID tenant, Map<String, Object> r) {
        var result =
                (tools.jackson.databind.node.ObjectNode)
                        dto(
                                r,
                                "id",
                                "patient_id",
                                "appointment_id",
                                "author_user_id",
                                "note_type",
                                "occurred_at",
                                "status",
                                "version",
                                "signed_at",
                                "revision_of_id");
        for (String field : List.of("narrative", "plan", "referrals"))
            result.put(field, decrypt(tenant, r, field));
        if (r.get("content_sha256") != null)
            result.put("sha256", HexFormat.of().formatHex((byte[]) r.get("content_sha256")));
        return result;
    }
}
