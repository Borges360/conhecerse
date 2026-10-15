package com.psicocare.patients;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class PatientsModule implements ModuleHandler, PatientEnrollment {
    private final Store db;
    private final Crypto crypto;
    private final Access access;
    private final Events events;

    public PatientsModule(Store db, Crypto crypto, Access access, Events events) {
        this.db = db;
        this.crypto = crypto;
        this.access = access;
        this.events = events;
    }

    public boolean supports(Command c) {
        return Set.of("Patients", "Privacy").contains(c.tag())
                || c.path().endsWith("/portal/me")
                || c.path().endsWith("/portal/patients");
    }

    public UUID enroll(Actor a, String name, String email, UUID professional) {
        return uuid(
                execute(
                        new Command(
                                "POST",
                                "/v1/orgs/{orgId}/patients",
                                "Patients",
                                "PATIENT_WRITE",
                                Map.of(),
                                Map.of(),
                                object(
                                        "fullName",
                                        name,
                                        "email",
                                        email,
                                        "professionalId",
                                        professional),
                                a,
                                0,
                                null,
                                UUID.randomUUID())),
                "id");
    }

    public JsonNode execute(Command c) {
        Actor a = c.actor();
        UUID tenant = a.tenant();
        JsonNode b = c.body();
        String path = c.path();
        if (path.endsWith("/portal/patients"))
            return c.page(
                    db
                            .rows(
                                    "select * from app.patients where tenant_id=? order by"
                                        + " created_at",
                                    tenant)
                            .stream()
                            .filter(
                                    p ->
                                            access.portal(a, id(p, "id"))
                                                    || access.guardian(a, id(p, "id"), "SCHEDULE"))
                            .map(p -> patient(p, tenant, false))
                            .toList());
        if (path.endsWith("/portal/me")) {
            var record =
                    c.query().containsKey("patientId")
                            ? db.one(
                                    "select * from app.patients where tenant_id=? and id=?",
                                    tenant,
                                    UUID.fromString(c.query().get("patientId")))
                            : db.optional(
                                            "select * from app.patients where tenant_id=? and"
                                                + " portal_user_id=?",
                                            tenant,
                                            a.user())
                                    .orElseGet(
                                            () ->
                                                    db.one(
                                                            "select p.* from app.patients p join"
                                                                + " app.guardian_patient_access g"
                                                                + " on g.tenant_id=p.tenant_id and"
                                                                + " g.patient_id=p.id where"
                                                                + " p.tenant_id=? and"
                                                                + " g.guardian_user_id=? and"
                                                                + " g.legal_verified_at is not null"
                                                                + " and not g.review_required and"
                                                                + " g.valid_from<=now() and"
                                                                + " (g.valid_until is null or"
                                                                + " g.valid_until>now()) and"
                                                                + " 'SCHEDULE'=any(g.access_scope)"
                                                                + " order by p.created_at limit 1",
                                                            tenant,
                                                            a.user()));
            Problem.require(
                    access.portal(a, id(record, "id"))
                            || access.guardian(a, id(record, "id"), "SCHEDULE"),
                    404,
                    "RESOURCE_NOT_FOUND");
            return patient(record, tenant, access.portal(a, id(record, "id")));
        }
        if (path.contains("/privacy-requests") && !c.vars().containsKey("patientId")) {
            a.require("OWNER", "ADMIN");
            var r =
                    db.one(
                            "select * from app.privacy_requests where tenant_id=? and id=? for"
                                    + " update",
                            tenant,
                            c.id("requestId"));
            Problem.require(
                    Set.of("OPEN", "IN_REVIEW").contains(str(r, "status")),
                    409,
                    "INVALID_TRANSITION");
            Problem.require(
                    !str(r, "request_type").equals("ERASURE")
                            || !text(b, "status").equals("FULFILLED"),
                    422,
                    "RETENTION_REVIEW_REQUIRED");
            Problem.require(
                    !Set.of("ACCESS", "PORTABILITY").contains(str(r, "request_type"))
                            || !text(b, "status").equals("FULFILLED"),
                    422,
                    "AUTHENTICATED_DELIVERY_REQUIRED");
            db.exec(
                    "update app.privacy_requests set status=?,decision_code=?,completed_at=case"
                        + " when ? in ('FULFILLED','DENIED_WITH_REASON') then now() else null end"
                        + " where tenant_id=? and id=?",
                    text(b, "status"),
                    text(b, "decisionCode"),
                    text(b, "status"),
                    tenant,
                    c.id("requestId"));
            events.audit(a, "PrivacyRequestReviewed", "privacyRequest", c.id("requestId"));
            return object("id", c.id("requestId"), "version", 1);
        }
        if (c.vars().containsKey("patientId")) {
            UUID pid = c.id("patientId");
            if (path.endsWith("/privacy-requests")) {
                Problem.require(
                        a.has("OWNER", "ADMIN")
                                || access.portal(a, pid)
                                || access.guardian(a, pid, "SCHEDULE"),
                        404,
                        "RESOURCE_NOT_FOUND");
                if (c.isGet())
                    return c.page(
                            db
                                    .rows(
                                            "select * from app.privacy_requests where tenant_id=?"
                                                    + " and patient_id=? order by opened_at desc",
                                            tenant,
                                            pid)
                                    .stream()
                                    .map(
                                            r ->
                                                    dto(
                                                            r,
                                                            "id",
                                                            "request_type",
                                                            "status",
                                                            "opened_at",
                                                            "decision_code"))
                                    .toList());
                UUID id = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.privacy_requests(tenant_id,id,patient_id,request_type,status,requested_by)"
                            + " values(?,?,?,?,'OPEN',?)",
                        tenant,
                        id,
                        pid,
                        text(b, "type"),
                        a.user());
                events.emit(a, "PrivacyRequestCreated", "privacyRequest", id, 1);
                return object("id", id, "version", 1);
            }
            access.patient(a, pid, false);
            var r =
                    db.one(
                            "select * from app.patients where tenant_id=? and id=?"
                                    + (c.isGet() ? "" : " for update"),
                            tenant,
                            pid);
            if (path.endsWith("/assignments")) {
                if (c.isGet())
                    return c.page(
                            db
                                    .rows(
                                            "select * from app.care_assignments where tenant_id=?"
                                                    + " and patient_id=? order by start_at desc",
                                            tenant,
                                            pid)
                                    .stream()
                                    .map(
                                            x ->
                                                    dto(
                                                            x,
                                                            "id",
                                                            "professional_id",
                                                            "patient_id",
                                                            "start_at",
                                                            "end_at"))
                                    .toList());
                a.require("OWNER", "ADMIN");
                UUID pro = uuid(b, "professionalId");
                requireVerified(tenant, pro);
                if (db.exists(
                        "select 1 from app.care_assignments where tenant_id=? and patient_id=? and"
                                + " end_at is null",
                        tenant,
                        pid))
                    Problem.require(
                            b.path("handoffEvidence").asText().length() >= 8,
                            422,
                            "HANDOFF_EVIDENCE_REQUIRED");
                OffsetDateTime begins =
                        b.has("startAt")
                                ? OffsetDateTime.parse(text(b, "startAt"))
                                : OffsetDateTime.now();
                Problem.require(
                        !begins.isBefore(OffsetDateTime.now().minusSeconds(60)),
                        422,
                        "INVALID_ASSIGNMENT_START");
                db.exec(
                        "update app.care_assignments set end_at=? where tenant_id=? and"
                                + " patient_id=? and end_at is null",
                        begins,
                        tenant,
                        pid);
                UUID id = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.care_assignments(tenant_id,id,patient_id,professional_id,start_at,transfer_approved_at,transfer_evidence_cipher)"
                            + " values(?,?,?,?,?,now(),?)",
                        tenant,
                        id,
                        pid,
                        pro,
                        begins,
                        text(b, "handoffEvidence").isBlank()
                                ? null
                                : crypto.encrypt(
                                        tenant,
                                        "care_assignments.evidence",
                                        id,
                                        1,
                                        text(b, "handoffEvidence")));
                events.audit(a, "CareAssignmentCreated", "careAssignment", id);
                return dto(
                        db.one(
                                "select * from app.care_assignments where tenant_id=? and id=?",
                                tenant,
                                id),
                        "id",
                        "patient_id",
                        "professional_id",
                        "start_at");
            }
            if (path.endsWith("/guardians")) {
                if (c.isGet()) {
                    Problem.require(
                            a.has("OWNER", "ADMIN") || access.clinical(a, pid),
                            404,
                            "RESOURCE_NOT_FOUND");
                    return c.page(
                            db
                                    .rows(
                                            "select g.*,r.id as"
                                                + " clinical_review_id,r.legal_reviewer,r.clinical_reviewer,r.expires_at"
                                                + " as clinical_expires_at from"
                                                + " app.guardian_patient_access g left join"
                                                + " app.guardian_clinical_reviews r on"
                                                + " r.tenant_id=g.tenant_id and"
                                                + " r.guardian_access_id=g.id and r.revoked_at is"
                                                + " null where g.tenant_id=? and g.patient_id=?",
                                            tenant,
                                            pid)
                                    .stream()
                                    .map(
                                            g ->
                                                    dto(
                                                            g,
                                                            "id",
                                                            "guardian_user_id",
                                                            "relationship",
                                                            "valid_until",
                                                            "clinical_review_id",
                                                            "legal_reviewer",
                                                            "clinical_reviewer",
                                                            "clinical_expires_at"))
                                    .toList());
                }
                a.require("OWNER", "ADMIN");
                UUID id = UUID.randomUUID();
                String[] scopes = Json.MAPPER.convertValue(b.path("accessScope"), String[].class);
                db.exec(
                        "insert into"
                            + " app.guardian_patient_access(tenant_id,id,patient_id,guardian_user_id,relationship,access_scope,legal_verified_at,review_required,evidence_cipher)"
                            + " values(?,?,?,?,?,?,now(),false,?)",
                        tenant,
                        id,
                        pid,
                        uuid(b, "guardianUserId"),
                        text(b, "relationship"),
                        scopes,
                        crypto.encrypt(
                                tenant,
                                "guardian_patient_access.evidence",
                                id,
                                1,
                                text(b, "evidence")));
                events.audit(a, "GuardianAccessReviewed", "guardianAccess", id);
                return object("id", id, "version", 1);
            }
            if (path.endsWith("/invite")) {
                access.patient(a, pid, true);
                String email = decrypt(r, tenant, "email");
                Problem.require(!email.isBlank(), 422, "VERIFIED_CHANNEL_REQUIRED");
                Problem.require(text(b, "channel").equals("EMAIL"), 422, "EMAIL_INVITE_REQUIRED");
                UUID id = UUID.randomUUID();
                var nonce = new byte[32];
                new SecureRandom().nextBytes(nonce);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
                db.exec(
                        "insert into"
                            + " app.action_tokens(tenant_id,id,token_hash,purpose,patient_id,email_lookup_hmac,delivery_cipher,expires_at)"
                            + " values(?,?,?,'INVITE',?,?,?,now()+interval '72 hours')",
                        tenant,
                        id,
                        hash(token),
                        pid,
                        crypto.lookup(tenant, email),
                        crypto.encrypt(
                                tenant,
                                "action_tokens",
                                id,
                                1,
                                object("token", token, "email", email).toString()));
                db.exec(
                        "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                                + " values(?,'INVITE',?,?::jsonb)",
                        tenant,
                        id.toString(),
                        object("tokenId", id).toString());
                events.emit(a, "PatientInvited", "patient", pid, version(r));
                return object("id", pid, "version", version(r));
            }
            if (c.isGet()) return patient(r, tenant, true);
            c.version(r);
            boolean own = access.portal(a, pid);
            if (own) Problem.require(!b.has("fullName"), 403, "LEGAL_IDENTITY_REVIEW_REQUIRED");
            else access.patient(a, pid, true);
            ObjectNode data =
                    object(
                            "fullName",
                            decrypt(r, tenant, "name"),
                            "socialName",
                            decrypt(r, tenant, "social_name"),
                            "email",
                            decrypt(r, tenant, "email"),
                            "phone",
                            decrypt(r, tenant, "phone"),
                            "cpf",
                            decrypt(r, tenant, "cpf"));
            b.properties().forEach(f -> data.set(f.getKey(), f.getValue()));
            int v = version(r) + 1;
            db.exec(
                    "update app.patients set"
                        + " name_cipher=?,name_lookup_hmac=?,social_name_cipher=?,email_cipher=?,email_lookup_hmac=?,phone_cipher=?,phone_lookup_hmac=?,cpf_cipher=?,communication_preference=?,version=?,updated_at=now()"
                        + " where tenant_id=? and id=?",
                    enc(tenant, pid, v, "name", text(data, "fullName")),
                    crypto.lookup(tenant, text(data, "fullName")),
                    enc(tenant, pid, v, "social_name", text(data, "socialName")),
                    enc(tenant, pid, v, "email", text(data, "email")),
                    lookup(tenant, text(data, "email")),
                    enc(tenant, pid, v, "phone", text(data, "phone")),
                    lookup(tenant, text(data, "phone")),
                    enc(tenant, pid, v, "cpf", text(data, "cpf")),
                    b.path("communicationPreference").asText(str(r, "communication_preference")),
                    v,
                    tenant,
                    pid);
            saveOptional(a, pid, b);
            events.audit(a, "PatientUpdated", "patient", pid);
            return patient(
                    db.one("select * from app.patients where tenant_id=? and id=?", tenant, pid),
                    tenant,
                    true);
        }
        if (c.isGet()) {
            List<Map<String, Object>> rows;
            if (c.query().containsKey("q"))
                rows =
                        db.rows(
                                "select * from app.patients where tenant_id=? and"
                                        + " name_lookup_hmac=? order by created_at desc,id limit ?"
                                        + " offset ?",
                                tenant,
                                crypto.lookup(tenant, c.query().get("q")),
                                c.limit() + 1,
                                c.offset());
            else
                rows =
                        db.rows(
                                "select * from app.patients where tenant_id=? order by created_at"
                                        + " desc,id limit ? offset ?",
                                tenant,
                                c.limit() + 1,
                                c.offset());
            return c.sqlPage(rows.stream().map(r -> patient(r, tenant, false)).toList());
        }
        UUID pid = UUID.randomUUID();
        UUID pro =
                b.has("professionalId")
                        ? uuid(b, "professionalId")
                        : a.has("PSYCHOLOGIST") ? access.professional(a) : null;
        if (pro != null) {
            requireVerified(tenant, pro);
            if (a.has("PSYCHOLOGIST") && !a.has("OWNER", "ADMIN", "SECRETARY"))
                Problem.require(pro.equals(access.professional(a)), 404, "RESOURCE_NOT_FOUND");
        }
        db.exec(
                "insert into"
                    + " app.patients(tenant_id,id,name_cipher,name_lookup_hmac,social_name_cipher,cpf_cipher,cpf_lookup_hmac,email_cipher,email_lookup_hmac,phone_cipher,phone_lookup_hmac,birth_date,communication_preference)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenant,
                pid,
                enc(tenant, pid, 1, "name", text(b, "fullName")),
                crypto.lookup(tenant, text(b, "fullName")),
                enc(tenant, pid, 1, "social_name", text(b, "socialName")),
                enc(tenant, pid, 1, "cpf", text(b, "cpf")),
                lookup(tenant, text(b, "cpf")),
                enc(tenant, pid, 1, "email", text(b, "email")),
                lookup(tenant, text(b, "email")),
                enc(tenant, pid, 1, "phone", text(b, "phone")),
                lookup(tenant, text(b, "phone")),
                b.has("birthDate") ? LocalDate.parse(text(b, "birthDate")) : null,
                b.path("communicationPreference").asText("EMAIL"));
        if (pro != null)
            db.exec(
                    "insert into app.care_assignments(tenant_id,patient_id,professional_id)"
                            + " values(?,?,?)",
                    tenant,
                    pid,
                    pro);
        saveOptional(a, pid, b);
        events.audit(a, "PatientCreated", "patient", pid);
        return patient(
                db.one("select * from app.patients where tenant_id=? and id=?", tenant, pid),
                tenant,
                true);
    }

    private void requireVerified(UUID tenant, UUID professional) {
        Problem.require(
                db.exists(
                        "select 1 from app.professionals p join app.organization_memberships m on"
                            + " m.tenant_id=p.tenant_id and m.user_id=p.user_id where p.tenant_id=?"
                            + " and p.id=? and p.verification_status='VERIFIED' and"
                            + " m.status='ACTIVE' and 'PSYCHOLOGIST'=any(m.roles)",
                        tenant,
                        professional),
                422,
                "VERIFIED_PROFESSIONAL_REQUIRED");
    }

    private void saveOptional(Actor a, UUID pid, JsonNode b) {
        for (var entry :
                Map.of("address", "address_cipher", "emergencyContact", "emergency_contact_cipher")
                        .entrySet()) {
            if (!b.has(entry.getKey())) continue;
            if (entry.getKey().equals("emergencyContact") && !b.get(entry.getKey()).isNull())
                Problem.require(access.clinical(a, pid), 403, "CLINICAL_INDICATION_REQUIRED");
            db.exec(
                    "update app.patients set " + entry.getValue() + "=? where tenant_id=? and id=?",
                    b.get(entry.getKey()).isNull()
                            ? null
                            : crypto.encrypt(
                                    a.tenant(),
                                    "patients." + entry.getKey(),
                                    pid,
                                    1,
                                    b.get(entry.getKey()).toString()),
                    a.tenant(),
                    pid);
        }
        if (b.has("birthDate"))
            db.exec(
                    "update app.patients set birth_date=? where tenant_id=? and id=?",
                    b.get("birthDate").isNull() ? null : LocalDate.parse(text(b, "birthDate")),
                    a.tenant(),
                    pid);
    }

    private byte[] enc(UUID t, UUID id, int v, String field, String s) {
        return s.isBlank() ? null : crypto.encrypt(t, "patients." + field, id, v, s);
    }

    private byte[] lookup(UUID t, String s) {
        return s.isBlank() ? null : crypto.lookup(t, s);
    }

    private String decrypt(Map<String, Object> r, UUID tenant, String field) {
        return r.get(field + "_cipher") == null
                ? ""
                : crypto.decrypt(
                        tenant,
                        "patients." + field,
                        id(r, "id"),
                        version(r),
                        (byte[]) r.get(field + "_cipher"));
    }

    private JsonNode patient(Map<String, Object> r, UUID tenant, boolean detail) {
        UUID pid = id(r, "id");
        String name = decrypt(r, tenant, "name"), social = decrypt(r, tenant, "social_name");
        var o =
                object(
                        "id",
                        pid,
                        "displayName",
                        social.isBlank() ? name : social,
                        "status",
                        r.get("status"),
                        "birthDate",
                        r.get("birth_date") == null ? null : r.get("birth_date").toString(),
                        "version",
                        version(r),
                        "assignedProfessionals",
                        db
                                .rows(
                                        "select professional_id from app.care_assignments where"
                                            + " tenant_id=? and patient_id=? and end_at is null",
                                        tenant,
                                        pid)
                                .stream()
                                .map(x -> x.get("professional_id"))
                                .toList());
        if (r.get("birth_date") == null) o.remove("birthDate");
        if (detail) {
            o.put("fullName", name);
            o.put("socialName", social);
            o.put("email", decrypt(r, tenant, "email"));
            o.put("phone", decrypt(r, tenant, "phone"));
            o.put("communicationPreference", str(r, "communication_preference"));
            if (r.get("address_cipher") != null)
                o.set(
                        "address",
                        parse(
                                crypto.decrypt(
                                        tenant,
                                        "patients.address",
                                        pid,
                                        1,
                                        (byte[]) r.get("address_cipher"))));
            if (r.get("emergency_contact_cipher") != null
                    && Boolean.TRUE.equals(
                            db.jdbc.queryForObject(
                                    "select app.can_access_clinical(?)", Boolean.class, pid)))
                o.set(
                        "emergencyContact",
                        parse(
                                crypto.decrypt(
                                        tenant,
                                        "patients.emergencyContact",
                                        pid,
                                        1,
                                        (byte[]) r.get("emergency_contact_cipher"))));
        }
        return o;
    }
}
