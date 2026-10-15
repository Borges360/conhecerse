package com.psicocare.clinical;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Service
public class EmergencyAccess implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Crypto crypto;
    private final Events events;

    public EmergencyAccess(Store db, Access access, Crypto crypto, Events events) {
        this.db = db;
        this.access = access;
        this.crypto = crypto;
        this.events = events;
    }

    public boolean supports(Command c) {
        return c.tag().equals("EmergencyAccess");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID t = a.tenant();
        a.require("OWNER", "ADMIN", "PSYCHOLOGIST");
        a.recentMfa();
        if (!c.vars().containsKey("accessId")) {
            if (c.isGet())
                return c.page(
                        db
                                .rows(
                                        "select * from app.break_glass_requests where tenant_id=?"
                                            + " order by created_at desc",
                                        t)
                                .stream()
                                .filter(
                                        r ->
                                                a.has("OWNER", "ADMIN")
                                                        || a.user().equals(r.get("requested_by"))
                                                        || access.clinical(a, id(r, "patient_id")))
                                .map(
                                        r -> {
                                            var o =
                                                    (tools.jackson.databind.node.ObjectNode)
                                                            dto(
                                                                    r,
                                                                    "id",
                                                                    "patient_id",
                                                                    "requested_by",
                                                                    "scope",
                                                                    "status",
                                                                    "expires_at",
                                                                    "request_expires_at",
                                                                    "duration_minutes");
                                            o.put(
                                                    "reason",
                                                    crypto.decrypt(
                                                            t,
                                                            "break_glass_requests",
                                                            id(r, "id"),
                                                            1,
                                                            (byte[]) r.get("reason_cipher")));
                                            return o;
                                        })
                                .toList());
            UUID id = UUID.randomUUID(), patient = uuid(c.body(), "patientId");
            access.patient(a, patient, false);
            Problem.require(
                    db.exists(
                            "select 1 from app.release_approvals where tenant_id=? and"
                                + " gate_code='GATE-SECURITY-01'",
                            t),
                    422,
                    "SECURITY_APPROVAL_REQUIRED");
            db.exec(
                    "insert into"
                        + " app.break_glass_requests(tenant_id,id,patient_id,requested_by,reason_cipher,scope,duration_minutes)"
                        + " values(?,?,?,?,?,?,?)",
                    t,
                    id,
                    patient,
                    a.user(),
                    crypto.encrypt(t, "break_glass_requests", id, 1, text(c.body(), "reason")),
                    text(c.body(), "scope"),
                    c.body().path("durationMinutes").asInt());
            events.audit(a, "EmergencyAccessRequested", "emergencyAccess", id);
            return object("id", id, "version", 1);
        }
        UUID id = c.id("accessId");
        var r =
                db.one(
                        "select * from app.break_glass_requests where tenant_id=? and id=? for"
                            + " update",
                        t,
                        id);
        UUID patient = id(r, "patient_id");
        if (c.tail().equals("notes")) {
            Problem.require(
                    a.user().equals(r.get("requested_by"))
                            && db.jdbc.queryForObject(
                                    "select app.has_emergency_read(?)", Boolean.class, patient),
                    404,
                    "RESOURCE_NOT_FOUND");
            events.audit(a, "EmergencyClinicalRead", "emergencyAccess", id);
            return c.sqlPage(
                    db
                            .rows(
                                    "select * from app.clinical_notes where tenant_id=? and"
                                        + " patient_id=? and status='SIGNED' order by occurred_at"
                                        + " desc limit ? offset ?",
                                    t,
                                    patient,
                                    c.limit() + 1,
                                    c.offset())
                            .stream()
                            .map(
                                    n -> {
                                        var o =
                                                (tools.jackson.databind.node.ObjectNode)
                                                        dto(
                                                                n,
                                                                "id",
                                                                "occurred_at",
                                                                "signed_at",
                                                                "note_type");
                                        o.put(
                                                "narrative",
                                                crypto.decrypt(
                                                        t,
                                                        "clinical_notes.narrative",
                                                        id(n, "id"),
                                                        version(n),
                                                        (byte[]) n.get("narrative_cipher")));
                                        return o;
                                    })
                            .toList());
        }
        if (c.tail().equals("approve")) {
            Problem.require(
                    str(r, "status").equals("PENDING") && !a.user().equals(r.get("requested_by")),
                    403,
                    "INDEPENDENT_REVIEW_REQUIRED");
            Problem.require(
                    db.exists(
                            "select 1 from app.break_glass_requests where tenant_id=? and id=? and"
                                + " request_expires_at>now()",
                            t,
                            id),
                    403,
                    "EMERGENCY_REQUEST_EXPIRED");
            String role = text(c.body(), "reviewRole");
            if (role.equals("SECURITY")) a.require("OWNER", "ADMIN");
            else access.requireClinical(a, patient);
            Problem.require(
                    !db.exists(
                            "select 1 from app.break_glass_approvals where tenant_id=? and"
                                + " request_id=? and (review_role=? or reviewer_user_id=?)",
                            t,
                            id,
                            role,
                            a.user()),
                    409,
                    "INDEPENDENT_REVIEW_REQUIRED");
            db.exec(
                    "insert into"
                        + " app.break_glass_approvals(tenant_id,request_id,reviewer_user_id,review_role,evidence_cipher)"
                        + " values(?,?,?,?,?)",
                    t,
                    id,
                    a.user(),
                    role,
                    crypto.encrypt(t, "break_glass_approvals", id, 1, text(c.body(), "evidence")));
            db.exec(
                    "update app.break_glass_requests set"
                        + " status='ACTIVE',expires_at=now()+make_interval(mins=>duration_minutes)"
                        + " where tenant_id=? and id=? and (select count(*) from"
                        + " app.break_glass_approvals where tenant_id=? and request_id=?)=2",
                    t,
                    id,
                    t,
                    id);
            events.audit(a, "EmergencyAccessApproved", "emergencyAccess", id);
        } else {
            Problem.require(
                    a.has("OWNER", "ADMIN")
                            || a.user().equals(r.get("requested_by"))
                            || access.clinical(a, patient),
                    404,
                    "RESOURCE_NOT_FOUND");
            if (c.tail().equals("deny"))
                Problem.require(str(r, "status").equals("PENDING"), 409, "INVALID_TRANSITION");
            db.exec(
                    "update app.break_glass_requests set status=?,revoked_at=now() where"
                        + " tenant_id=? and id=?",
                    c.tail().equals("deny") ? "DENIED" : "REVOKED",
                    t,
                    id);
            events.audit(a, "EmergencyAccessRevoked", "emergencyAccess", id);
        }
        return object("id", id, "version", 1);
    }
}
