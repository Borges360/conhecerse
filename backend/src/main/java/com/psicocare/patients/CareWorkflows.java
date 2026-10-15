package com.psicocare.patients;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.documents.DocumentsModule;
import com.psicocare.documents.FileService;
import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class CareWorkflows implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Crypto crypto;
    private final Events events;
    private final FileService files;

    public CareWorkflows(Store db, Access access, Crypto crypto, Events events, FileService files) {
        this.db = db;
        this.access = access;
        this.crypto = crypto;
        this.events = events;
        this.files = files;
    }

    public boolean supports(Command c) {
        return c.tag().equals("CareWorkflows");
    }

    public JsonNode execute(Command c) {
        Actor a = c.actor();
        UUID t = a.tenant();
        JsonNode b = c.body();
        if (c.path().contains("/remote-protocols")) return remote(c);
        if (c.path().contains("/guardians/")) return guardian(c);
        if (c.path().endsWith("/privacy-requests")) {
            a.require("OWNER", "ADMIN", "PSYCHOLOGIST");
            return c.page(
                    db
                            .rows(
                                    "select * from app.privacy_requests where tenant_id=? order by"
                                        + " due_at,id",
                                    t)
                            .stream()
                            .filter(
                                    r ->
                                            a.has("OWNER", "ADMIN")
                                                    || access.clinical(a, id(r, "patient_id")))
                            .map(
                                    r ->
                                            dto(
                                                    r,
                                                    "id",
                                                    "patient_id",
                                                    "request_type",
                                                    "status",
                                                    "requested_by",
                                                    "opened_at",
                                                    "due_at",
                                                    "completed_at",
                                                    "version"))
                            .toList());
        }
        if (c.path().endsWith("/prepare-export")) return prepare(c);
        if (!c.vars().containsKey("exportId")) {
            return c.page(
                    db
                            .rows(
                                    "select * from app.record_exports where tenant_id=? order by"
                                        + " created_at desc",
                                    t)
                            .stream()
                            .filter(
                                    r ->
                                            a.user().equals(r.get("recipient_user_id"))
                                                    || access.clinical(a, id(r, "patient_id")))
                            .map(
                                    r ->
                                            dto(
                                                    r,
                                                    "id",
                                                    "request_id",
                                                    "patient_id",
                                                    "file_id",
                                                    "status",
                                                    "expires_at",
                                                    "delivered_at",
                                                    "prepared_by",
                                                    "reviewed_by"))
                            .toList());
        }
        UUID id = c.id("exportId");
        var row =
                db.one(
                        "select * from app.record_exports where tenant_id=? and id=? for update",
                        t,
                        id);
        UUID patient = id(row, "patient_id");
        switch (c.tail()) {
            case "preview" -> {
                access.requireClinical(a, patient);
                return files.ticket(a, id(row, "file_id"));
            }
            case "release" -> {
                access.requireClinical(a, patient);
                a.recentMfa();
                gate(t, "GATE-CLINICAL-01");
                Problem.require(str(row, "status").equals("PREPARED"), 409, "INVALID_TRANSITION");
                Instant expiry =
                        OffsetDateTime.parse(text(b, "expiresAt"))
                                .toInstant()
                                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                validExpiry(expiry, 7 * 86400);
                requireRecipient(t, patient, id(row, "recipient_user_id"));
                db.exec(
                        "update app.record_exports set"
                            + " status='RELEASED',reviewed_by=?,review_evidence_cipher=?,expires_at=?"
                            + " where tenant_id=? and id=?",
                        a.user(),
                        crypto.encrypt(t, "record_exports.review", id, 1, text(b, "evidence")),
                        OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC),
                        t,
                        id);
                events.audit(a, "RecordExportReleased", "recordExport", id);
            }
            case "revoke" -> {
                access.requireClinical(a, patient);
                a.recentMfa();
                db.exec(
                        "update app.record_exports set status='REVOKED' where tenant_id=? and id=?",
                        t,
                        id);
                events.audit(a, "RecordExportRevoked", "recordExport", id);
            }
            case "download" -> {
                Problem.require(
                        a.user().equals(row.get("recipient_user_id")), 404, "RESOURCE_NOT_FOUND");
                requireRecipient(t, patient, a.user());
                Problem.require(
                        Set.of("RELEASED", "DELIVERED").contains(str(row, "status"))
                                && instant(row, "expires_at").isAfter(Instant.now()),
                        403,
                        "EXPORT_EXPIRED");
                return files.ticket(a, id(row, "file_id"));
            }
            default -> throw Problem.notFound();
        }
        return object("id", id, "version", 1);
    }

    private JsonNode remote(Command c) {
        var a = c.actor();
        UUID t = a.tenant(), patient = c.id("patientId");
        Problem.require(
                access.clinical(a, patient) || access.portal(a, patient),
                404,
                "RESOURCE_NOT_FOUND");
        if (c.isGet())
            return c.page(
                    db
                            .rows(
                                    "select * from app.remote_protocols where tenant_id=? and"
                                        + " patient_id=? order by version desc",
                                    t,
                                    patient)
                            .stream()
                            .map(
                                    r -> {
                                        var dto =
                                                (tools.jackson.databind.node.ObjectNode)
                                                        dto(
                                                                r,
                                                                "id",
                                                                "version",
                                                                "status",
                                                                "reviewed_by",
                                                                "reviewed_at",
                                                                "created_at");
                                        dto.set(
                                                "protocol",
                                                parse(
                                                        crypto.decrypt(
                                                                t,
                                                                "remote_protocols",
                                                                id(r, "id"),
                                                                version(r),
                                                                (byte[]) r.get("payload_cipher"))));
                                        return dto;
                                    })
                            .toList());
        if (c.path().endsWith("/review")) {
            access.requireClinical(a, patient);
            a.recentMfa();
            gate(t, "GATE-CLINICAL-01");
            UUID id = c.id("protocolId");
            var r =
                    db.one(
                            "select * from app.remote_protocols where tenant_id=? and patient_id=?"
                                + " and id=? for update",
                            t,
                            patient,
                            id);
            Problem.require(str(r, "status").equals("PENDING"), 409, "INVALID_TRANSITION");
            db.exec(
                    "update app.remote_protocols set"
                        + " status=?,reviewed_by=?,reviewed_at=now(),evidence_cipher=? where"
                        + " tenant_id=? and id=?",
                    text(c.body(), "decision").equals("APPROVE") ? "APPROVED" : "REJECTED",
                    a.user(),
                    crypto.encrypt(t, "remote_protocols.review", id, 1, text(c.body(), "evidence")),
                    t,
                    id);
            events.audit(a, "RemoteProtocolReviewed", "remoteProtocol", id);
            return object("id", id, "version", version(r));
        }
        var b = c.body();
        Problem.require(
                b.path("privacyAcknowledged").asBoolean(), 422, "PRIVACY_GUIDANCE_REQUIRED");
        if (b.has("currentLocation") || b.has("emergencyContact")) {
            Problem.require(
                    access.clinical(a, patient) && text(b, "clinicalIndication").length() >= 8,
                    403,
                    "CLINICAL_INDICATION_REQUIRED");
        }
        db.one("select id from app.patients where tenant_id=? and id=? for update", t, patient);
        int v =
                db.jdbc.queryForObject(
                        "select coalesce(max(version),0)+1 from app.remote_protocols where"
                            + " tenant_id=? and patient_id=?",
                        Integer.class,
                        t,
                        patient);
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.remote_protocols(tenant_id,id,patient_id,version,payload_cipher,submitted_by)"
                    + " values(?,?,?,?,?,?)",
                t,
                id,
                patient,
                v,
                crypto.encrypt(t, "remote_protocols", id, v, b.toString()),
                a.user());
        events.audit(a, "RemoteProtocolSubmitted", "remoteProtocol", id);
        return object("id", id, "version", v);
    }

    private JsonNode guardian(Command c) {
        var a = c.actor();
        UUID t = a.tenant(), patient = c.id("patientId"), guardian = c.id("guardianId");
        var g =
                db.one(
                        "select * from app.guardian_patient_access where tenant_id=? and id=? and"
                            + " patient_id=? for update",
                        t,
                        guardian,
                        patient);
        if (c.tail().equals("revoke-clinical")) {
            a.require("OWNER", "ADMIN");
            a.recentMfa();
            db.exec(
                    "update app.guardian_clinical_reviews set revoked_at=now() where tenant_id=?"
                        + " and guardian_access_id=? and revoked_at is null",
                    t,
                    guardian);
            events.audit(a, "GuardianClinicalAccessRevoked", "guardianAccess", guardian);
            return object("id", guardian, "version", 1);
        }
        a.recentMfa();
        gate(t, "GATE-LEGAL-01");
        gate(t, "GATE-CLINICAL-01");
        Problem.require(
                g.get("legal_verified_at") != null
                        && !Boolean.TRUE.equals(g.get("review_required")),
                422,
                "LEGAL_REVIEW_REQUIRED");
        Instant expiry =
                OffsetDateTime.parse(text(c.body(), "expiresAt"))
                        .toInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        validExpiry(expiry, 90 * 86400);
        if (c.tail().equals("legal-review")) {
            a.require("OWNER", "ADMIN");
            Problem.require(
                    !a.user().equals(g.get("guardian_user_id")),
                    403,
                    "INDEPENDENT_REVIEW_REQUIRED");
            db.exec(
                    "update app.guardian_clinical_reviews set revoked_at=now() where tenant_id=?"
                        + " and guardian_access_id=? and revoked_at is null",
                    t,
                    guardian);
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.guardian_clinical_reviews(tenant_id,id,guardian_access_id,patient_id,legal_reviewer,evidence_cipher,expires_at)"
                        + " values(?,?,?,?,?,?,?)",
                    t,
                    id,
                    guardian,
                    patient,
                    a.user(),
                    crypto.encrypt(
                            t, "guardian_clinical_reviews", id, 1, text(c.body(), "evidence")),
                    OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC));
        } else {
            access.requireClinical(a, patient);
            var review =
                    db.one(
                            "select * from app.guardian_clinical_reviews where tenant_id=? and"
                                + " guardian_access_id=? and revoked_at is null and"
                                + " expires_at>now() and clinical_reviewer is null order by"
                                + " created_at desc limit 1 for update",
                            t,
                            guardian);
            Problem.require(
                    !a.user().equals(review.get("legal_reviewer"))
                            && !a.user().equals(g.get("guardian_user_id")),
                    403,
                    "INDEPENDENT_REVIEW_REQUIRED");
            Problem.require(
                    !expiry.isAfter(instant(review, "expires_at")), 422, "REVIEW_EXPIRY_EXCEEDED");
            db.exec(
                    "update app.guardian_clinical_reviews set"
                        + " clinical_reviewer=?,expires_at=?,evidence_cipher=? where tenant_id=?"
                        + " and id=?",
                    a.user(),
                    OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC),
                    crypto.encrypt(
                            t,
                            "guardian_clinical_reviews",
                            id(review, "id"),
                            1,
                            object(
                                            "legalEvidence",
                                            crypto.decrypt(
                                                    t,
                                                    "guardian_clinical_reviews",
                                                    id(review, "id"),
                                                    1,
                                                    (byte[]) review.get("evidence_cipher")),
                                            "clinicalEvidence",
                                            text(c.body(), "evidence"))
                                    .toString()),
                    t,
                    review.get("id"));
        }
        events.audit(a, "GuardianClinicalAccessReviewed", "guardianAccess", guardian);
        return object("id", guardian, "version", 1);
    }

    private JsonNode prepare(Command c) {
        var a = c.actor();
        UUID t = a.tenant(), request = c.id("requestId");
        var r =
                db.one(
                        "select * from app.privacy_requests where tenant_id=? and id=? for update",
                        t,
                        request);
        UUID patient = id(r, "patient_id"), recipient = id(r, "requested_by");
        access.requireClinical(a, patient);
        a.recentMfa();
        Problem.require(
                Set.of("ACCESS", "PORTABILITY").contains(str(r, "request_type"))
                        && Set.of("OPEN", "IN_REVIEW").contains(str(r, "status")),
                409,
                "INVALID_TRANSITION");
        requireRecipient(t, patient, recipient);
        var notes = new ArrayList<JsonNode>();
        for (JsonNode noteId : c.body().path("noteIds")) {
            var note =
                    db.one(
                            "select * from app.clinical_notes where tenant_id=? and patient_id=?"
                                + " and id=? and status='SIGNED'",
                            t,
                            patient,
                            UUID.fromString(noteId.asText()));
            var data =
                    (tools.jackson.databind.node.ObjectNode)
                            dto(
                                    note,
                                    "id",
                                    "occurred_at",
                                    "signed_at",
                                    "note_type",
                                    "revision_of_id");
            for (String field : List.of("narrative", "plan", "referrals"))
                data.put(
                        field,
                        crypto.decrypt(
                                t,
                                "clinical_notes." + field,
                                id(note, "id"),
                                version(note),
                                (byte[]) note.get(field + "_cipher")));
            notes.add(data);
        }
        var p = db.one("select * from app.patients where tenant_id=? and id=?", t, patient);
        var identity = object("patientId", patient);
        for (String field : List.of("name", "social_name"))
            if (p.get(field + "_cipher") != null)
                identity.put(
                        field,
                        crypto.decrypt(
                                t,
                                "patients." + field,
                                patient,
                                version(p),
                                (byte[]) p.get(field + "_cipher")));
        var data =
                object(
                        "format",
                        "conhecerse-record-export-v1",
                        "requestId",
                        request,
                        "generatedAt",
                        Instant.now().toString(),
                        "patient",
                        identity,
                        "reviewedSummary",
                        text(c.body(), "reviewedSummary"),
                        "signedNotes",
                        notes);
        boolean portable = str(r, "request_type").equals("PORTABILITY");
        UUID file =
                files.saveExport(
                        a,
                        patient,
                        portable
                                ? data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                : DocumentsModule.renderPdf(
                                        "PACOTE PARA ACESSO REVISADO\n"
                                                + text(c.body(), "reviewedSummary")
                                                + "\n"
                                                + notes.stream()
                                                        .map(
                                                                n ->
                                                                        n.path("occurredAt")
                                                                                        .asText()
                                                                                + "\n"
                                                                                + n.path(
                                                                                                "narrative")
                                                                                        .asText()
                                                                                + "\nPlano: "
                                                                                + n.path("plan")
                                                                                        .asText()
                                                                                + "\n"
                                                                                + "Encaminhamentos:"
                                                                                + " "
                                                                                + n.path(
                                                                                                "referrals")
                                                                                        .asText())
                                                        .reduce("", (x, y) -> x + "\n\n" + y)),
                        portable ? "application/json" : "application/pdf");
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.record_exports(tenant_id,id,request_id,patient_id,recipient_user_id,file_id,prepared_by)"
                    + " values(?,?,?,?,?,?,?)",
                t,
                id,
                request,
                patient,
                recipient,
                file,
                a.user());
        db.exec(
                "update app.privacy_requests set status='IN_REVIEW',version=version+1 where"
                    + " tenant_id=? and id=?",
                t,
                request);
        events.audit(a, "RecordExportPrepared", "recordExport", id);
        return object("id", id, "version", 1, "fileId", file);
    }

    private void requireRecipient(UUID t, UUID patient, UUID recipient) {
        Problem.require(
                db.exists(
                                "select 1 from app.patients where tenant_id=? and id=? and"
                                    + " portal_user_id=?",
                                t,
                                patient,
                                recipient)
                        || db.exists(
                                "select 1 from app.guardian_patient_access g join"
                                    + " app.guardian_clinical_reviews r on r.tenant_id=g.tenant_id"
                                    + " and r.guardian_access_id=g.id where g.tenant_id=? and"
                                    + " g.patient_id=? and g.guardian_user_id=? and"
                                    + " g.legal_verified_at is not null and not g.review_required"
                                    + " and g.valid_from<=now() and (g.valid_until is null or"
                                    + " g.valid_until>now()) and r.clinical_reviewer is not null"
                                    + " and r.revoked_at is null and r.expires_at>now()",
                                t,
                                patient,
                                recipient),
                403,
                "REVIEWED_RECIPIENT_REQUIRED");
    }

    private void gate(UUID t, String code) {
        Problem.require(
                db.exists(
                        "select 1 from app.release_approvals where tenant_id=? and gate_code=?",
                        t,
                        code),
                422,
                "DOMAIN_APPROVAL_REQUIRED");
    }

    private void validExpiry(Instant expiry, long seconds) {
        Problem.require(
                expiry.isAfter(Instant.now())
                        && !expiry.isAfter(Instant.now().plusSeconds(seconds)),
                422,
                "INVALID_EXPIRY");
    }

    private Instant instant(Map<String, Object> row, String key) {
        var v = row.get(key);
        return v instanceof java.sql.Timestamp ts
                ? ts.toInstant()
                : ((OffsetDateTime) v).toInstant();
    }
}
