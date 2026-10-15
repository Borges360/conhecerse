package com.psicocare.video;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class VideoModule implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Crypto crypto;
    private final Events events;
    private final RoomGateway rooms;
    private final boolean enabled;
    private final String signaling, key, secret;

    public VideoModule(
            Store db,
            Access access,
            Crypto crypto,
            Events events,
            RoomGateway rooms,
            @Value("${app.video.enabled}") boolean enabled,
            @Value("${app.video.signaling-url}") String signaling,
            @Value("${app.video.api-key}") String key,
            @Value("${app.video.api-secret}") String secret) {
        this.db = db;
        this.access = access;
        this.crypto = crypto;
        this.events = events;
        this.rooms = rooms;
        this.enabled = enabled;
        this.signaling = signaling;
        this.key = key;
        this.secret = secret;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Video");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant(), id = c.id("appointmentId");
        var ap =
                db.one(
                        "select * from app.appointments where tenant_id=? and id=? for update",
                        tenant,
                        id);
        UUID patient = id(ap, "patient_id");
        access.appointment(a, ap, false);
        if (c.path().endsWith("/remote-contingency")) {
            boolean clinician =
                    access.clinical(a, patient)
                            && id(ap, "professional_id").equals(access.professional(a));
            Problem.require(clinician || access.portal(a, patient), 404, "RESOURCE_NOT_FOUND");
            var output = object();
            db.optional(
                            "select * from app.remote_protocols where tenant_id=? and patient_id=?"
                                    + " and status='APPROVED' order by version desc limit 1",
                            tenant,
                            patient)
                    .ifPresent(
                            r -> {
                                var protocol =
                                        parse(
                                                crypto.decrypt(
                                                        tenant,
                                                        "remote_protocols",
                                                        id(r, "id"),
                                                        version(r),
                                                        (byte[]) r.get("payload_cipher")));
                                for (String field : List.of("interruptionPlan", "privacyGuidance"))
                                    if (protocol.has(field)) output.set(field, protocol.get(field));
                                if (clinician)
                                    for (String field :
                                            List.of(
                                                    "acuteRiskPlan",
                                                    "currentLocation",
                                                    "emergencyContact"))
                                        if (protocol.has(field))
                                            output.set(field, protocol.get(field));
                            });
            if (clinician)
                db.optional(
                                "select emergency_contact_cipher from app.patients where"
                                        + " tenant_id=? and id=?",
                                tenant,
                                patient)
                        .filter(r -> r.get("emergency_contact_cipher") != null)
                        .ifPresent(
                                r ->
                                        output.set(
                                                "emergencyContact",
                                                parse(
                                                        crypto.decrypt(
                                                                tenant,
                                                                "patients.emergencyContact",
                                                                patient,
                                                                1,
                                                                (byte[])
                                                                        r.get(
                                                                                "emergency_contact_cipher")))));
            db.optional(
                            "select payload from app.tenant_settings_versions where tenant_id=?"
                                    + " order by version desc limit 1",
                            tenant)
                    .ifPresent(
                            r ->
                                    output.put(
                                            "contingencyChannel",
                                            parse(str(r, "payload"))
                                                    .path("contingencyChannel")
                                                    .asText("")));
            events.audit(a, "RemoteContingencyRead", "appointment", id);
            return output;
        }
        Problem.require(
                enabled
                        && db.exists(
                                "select 1 from app.release_approvals where tenant_id=? and"
                                        + " gate_code='GATE-VIDEO-01'",
                                tenant),
                503,
                "VIDEO_NOT_APPROVED");
        Problem.require(
                db.exists(
                        "select 1 from app.remote_protocols r join app.professionals p on"
                            + " p.tenant_id=r.tenant_id and p.user_id=r.reviewed_by join"
                            + " app.organization_memberships m on m.tenant_id=p.tenant_id and"
                            + " m.user_id=p.user_id where r.tenant_id=? and r.patient_id=? and"
                            + " r.status='APPROVED' and p.id=? and p.verification_status='VERIFIED'"
                            + " and m.status='ACTIVE' and 'PSYCHOLOGIST'=any(m.roles) and"
                            + " exists(select 1 from app.care_assignments ca where"
                            + " ca.tenant_id=r.tenant_id and ca.patient_id=r.patient_id and"
                            + " ca.professional_id=p.id and ca.start_at<=now() and (ca.end_at is"
                            + " null or ca.end_at>now())) and r.version=(select max(version) from"
                            + " app.remote_protocols where tenant_id=r.tenant_id and"
                            + " patient_id=r.patient_id)",
                        tenant,
                        patient,
                        ap.get("professional_id")),
                422,
                "REMOTE_PROTOCOL_REVIEW_REQUIRED");
        boolean clinician =
                access.clinical(a, patient)
                        && id(ap, "professional_id").equals(access.professional(a));
        Problem.require(
                clinician || access.portal(a, patient),
                404,
                "RESOURCE_NOT_FOUND"); // Guardians need a separate session-specific authorization;
        // deny conservatively.
        Instant start = instant(ap, "start_at"), end = instant(ap, "end_at"), now = Instant.now();
        Problem.require(
                str(ap, "modality").equals("REMOTE")
                        && Set.of("SCHEDULED", "CONFIRMED", "IN_PROGRESS")
                                .contains(str(ap, "status")),
                403,
                "VIDEO_WINDOW_CLOSED");
        Problem.require(
                !now.isBefore(start.minusSeconds(clinician ? 900 : 600))
                        && !now.isAfter(end.plusSeconds(1800)),
                403,
                "VIDEO_WINDOW_CLOSED");
        Problem.require(
                signaling.startsWith("wss://") && !secret.isBlank() && !key.isBlank(),
                503,
                "VIDEO_UNAVAILABLE");
        db.exec(
                "insert into app.video_rooms(tenant_id,appointment_id,provider_room_opaque_id)"
                        + " values(?,?,?) on conflict(tenant_id,appointment_id) do nothing",
                tenant,
                id,
                UUID.randomUUID().toString());
        String room =
                str(
                        db.one(
                                "select * from app.video_rooms where tenant_id=? and"
                                        + " appointment_id=?",
                                tenant,
                                id),
                        "provider_room_opaque_id");
        Problem.require(
                !db.exists(
                        "select 1 from app.video_rooms where tenant_id=? and appointment_id=? and"
                                + " closed_at is not null",
                        tenant,
                        id),
                403,
                "VIDEO_WINDOW_CLOSED");
        rooms.open(room);
        db.exec(
                "update app.video_rooms set opened_at=coalesce(opened_at,now()) where tenant_id=?"
                        + " and appointment_id=?",
                tenant,
                id);
        try {
            String identity =
                    HexFormat.of().formatHex(crypto.lookup(tenant, room + ":" + a.user()));
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(key)
                            .subject(identity)
                            .jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(now))
                            .notBeforeTime(Date.from(now.minusSeconds(5)))
                            .expirationTime(Date.from(now.plusSeconds(120)))
                            .claim(
                                    "video",
                                    Map.of(
                                            "roomJoin",
                                            true,
                                            "room",
                                            room,
                                            "canPublish",
                                            true,
                                            "canSubscribe",
                                            true,
                                            "canPublishData",
                                            false,
                                            "roomRecord",
                                            false))
                            .build();
            var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            token.sign(new MACSigner(secret));
            events.audit(a, "VideoAccessGranted", "appointment", id);
            return object(
                    "serverUrl",
                    signaling,
                    "accessToken",
                    token.serialize(),
                    "expiresAt",
                    now.plusSeconds(120).toString(),
                    "e2eeKey",
                    crypto.videoKey(room));
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(503, "VIDEO_UNAVAILABLE");
        }
    }

    public void provision(Actor a, UUID appointment) {
        if (!enabled
                || !db.exists(
                        "select 1 from app.release_approvals where tenant_id=? and"
                                + " gate_code='GATE-VIDEO-01'",
                        a.tenant())) return;
        if (!db.exists(
                "select 1 from app.appointments where tenant_id=? and id=? and modality='REMOTE'"
                    + " and status in ('SCHEDULED','CONFIRMED','IN_PROGRESS') and end_at+interval"
                    + " '30 minutes'>now()",
                a.tenant(),
                appointment)) return;
        db.exec(
                "insert into app.video_rooms(tenant_id,appointment_id,provider_room_opaque_id)"
                        + " values(?,?,?) on conflict(tenant_id,appointment_id) do nothing",
                a.tenant(),
                appointment,
                UUID.randomUUID().toString());
        var r =
                db.one(
                        "select * from app.video_rooms where tenant_id=? and appointment_id=?",
                        a.tenant(),
                        appointment);
        if (r.get("closed_at") != null) return;
        rooms.open(str(r, "provider_room_opaque_id"));
        db.exec(
                "update app.video_rooms set opened_at=coalesce(opened_at,now()) where tenant_id=?"
                        + " and appointment_id=?",
                a.tenant(),
                appointment);
    }

    /** The worker closes rooms after terminal transitions, expiry or loss of the clinical link. */
    public void sweep(Actor a) {
        if (!enabled) return;
        for (var r :
                db.rows(
                        "select vr.appointment_id,vr.provider_room_opaque_id from app.video_rooms"
                            + " vr join app.appointments ap on ap.tenant_id=vr.tenant_id and"
                            + " ap.id=vr.appointment_id join app.professionals p on"
                            + " p.tenant_id=ap.tenant_id and p.id=ap.professional_id where"
                            + " vr.tenant_id=? and vr.closed_at is null and (ap.status not in"
                            + " ('SCHEDULED','CONFIRMED','IN_PROGRESS') or ap.end_at+interval '30"
                            + " minutes'<now() or p.verification_status<>'VERIFIED' or not"
                            + " exists(select 1 from app.organization_memberships m where"
                            + " m.tenant_id=p.tenant_id and m.user_id=p.user_id and"
                            + " m.status='ACTIVE' and 'PSYCHOLOGIST'=any(m.roles)) or not"
                            + " exists(select 1 from app.care_assignments ca where"
                            + " ca.tenant_id=ap.tenant_id and ca.professional_id=ap.professional_id"
                            + " and ca.patient_id=ap.patient_id and ca.start_at<=now() and"
                            + " (ca.end_at is null or ca.end_at>now())))",
                        a.tenant())) {
            rooms.close(str(r, "provider_room_opaque_id"));
            db.exec(
                    "update app.video_rooms set closed_at=now() where tenant_id=? and"
                            + " appointment_id=?",
                    a.tenant(),
                    r.get("appointment_id"));
            events.audit(a, "VideoRoomClosed", "appointment", id(r, "appointment_id"));
        }
    }

    private Instant instant(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v instanceof java.sql.Timestamp t ? t.toInstant() : ((OffsetDateTime) v).toInstant();
    }
}
