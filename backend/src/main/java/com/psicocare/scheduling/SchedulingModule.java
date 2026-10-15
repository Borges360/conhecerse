package com.psicocare.scheduling;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class SchedulingModule implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Events events;
    private final Crypto crypto;
    private final PatientEnrollment enrollment;

    public SchedulingModule(
            Store db, Access access, Events events, Crypto crypto, PatientEnrollment enrollment) {
        this.db = db;
        this.access = access;
        this.events = events;
        this.crypto = crypto;
        this.enrollment = enrollment;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Scheduling")
                || c.path().endsWith("/portal/appointments")
                || c.path().equals("/v1/public/booking-requests");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        String path = c.path();
        if (path.equals("/v1/public/confirm")) return confirmToken(c);
        if (path.equals("/v1/public/booking-requests")) return publicBooking(c);
        if (path.contains("/booking-requests")) return bookingInbox(c);
        if (c.vars().containsKey("professionalId") && path.contains("/professionals/")) {
            UUID pro = c.id("professionalId");
            if (path.endsWith("/availability")) {
                if (c.isGet())
                    return c.page(
                            db
                                    .rows(
                                            "select * from app.availability_rules where tenant_id=?"
                                                    + " and professional_id=? order by"
                                                    + " weekday,local_start",
                                            tenant,
                                            pro)
                                    .stream()
                                    .map(
                                            r ->
                                                    dto(
                                                            r,
                                                            "id",
                                                            "weekday",
                                                            "local_start",
                                                            "local_end",
                                                            "timezone",
                                                            "effective_from",
                                                            "effective_until"))
                                    .toList());
                a.require("OWNER", "ADMIN");
                LocalTime start = time(text(b, "localStart")), end = time(text(b, "localEnd"));
                Problem.require(start.isBefore(end), 422, "INVALID_AVAILABILITY");
                zone(text(b, "timezone"));
                UUID id = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.availability_rules(tenant_id,id,professional_id,weekday,local_start,local_end,timezone,effective_from,effective_until)"
                            + " values(?,?,?,?,?,?,?,?,?)",
                        tenant,
                        id,
                        pro,
                        b.path("weekday").asInt(),
                        start,
                        end,
                        text(b, "timezone"),
                        LocalDate.parse(text(b, "effectiveFrom")),
                        b.has("effectiveUntil")
                                ? LocalDate.parse(text(b, "effectiveUntil"))
                                : null);
                events.audit(a, "AvailabilityCreated", "availability", id);
                return object("id", id, "version", 1);
            }
            a.require("OWNER", "ADMIN");
            OffsetDateTime from = OffsetDateTime.parse(text(b, "fromAt")),
                    to = OffsetDateTime.parse(text(b, "toAt"));
            Problem.require(from.isBefore(to), 422, "INVALID_AVAILABILITY");
            Problem.require(
                    !db.exists(
                            "select 1 from app.appointments where tenant_id=? and professional_id=?"
                                    + " and status in"
                                    + " ('REQUESTED','SCHEDULED','CONFIRMED','IN_PROGRESS') and"
                                    + " busy_range && tstzrange(?,?,'[)')",
                            tenant,
                            pro,
                            from,
                            to),
                    409,
                    "EXCEPTION_OVERLAPS_APPOINTMENT");
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.availability_exceptions(tenant_id,id,professional_id,from_at,to_at,reason_code)"
                        + " values(?,?,?,?,?,?)",
                    tenant,
                    id,
                    pro,
                    from,
                    to,
                    text(b, "reasonCode"));
            events.audit(a, "AvailabilityBlocked", "availabilityException", id);
            return object("id", id, "version", 1);
        }
        if (path.endsWith("/recurrence-series")) return recurrence(c);
        if (c.vars().containsKey("rescheduleId")) return resolveReschedule(c);
        if (path.endsWith("/reschedule-requests") && !c.vars().containsKey("appointmentId"))
            return c.page(
                    db
                            .rows(
                                    "select r.* from app.reschedule_requests r join"
                                            + " app.appointments ap on ap.tenant_id=r.tenant_id and"
                                            + " ap.id=r.appointment_id where r.tenant_id=? order by"
                                            + " requested_at desc",
                                    tenant)
                            .stream()
                            .map(this::rescheduleDto)
                            .toList());
        if (c.vars().containsKey("appointmentId")) {
            UUID id = c.id("appointmentId");
            var row =
                    db.one(
                            "select * from app.appointments where tenant_id=? and id=?"
                                    + (c.isGet() ? "" : " for update"),
                            tenant,
                            id);
            access.appointment(a, row, false);
            if (c.method().equals("PATCH")) {
                access.appointment(a, row, true);
                c.version(row);
                Problem.require(
                        Set.of("REQUESTED", "SCHEDULED", "CONFIRMED").contains(str(row, "status")),
                        409,
                        "IMMUTABLE_RESOURCE");
                db.exec(
                        "update app.appointments set"
                            + " reason_code=?,modality=?,version=version+1,updated_at=now() where"
                            + " tenant_id=? and id=?",
                        b.path("reasonCode").asText(str(row, "reason_code")),
                        b.path("modality").asText(str(row, "modality")),
                        tenant,
                        id);
                events.audit(a, "AppointmentDetailsUpdated", "appointment", id);
                return appointmentDto(
                        db.one(
                                "select * from app.appointments where tenant_id=? and id=?",
                                tenant,
                                id));
            }
            if (path.endsWith("/history"))
                return c.page(
                        db
                                .rows(
                                        "select * from app.appointment_history where tenant_id=?"
                                                + " and appointment_id=? order by changed_at",
                                        tenant,
                                        id)
                                .stream()
                                .map(
                                        r ->
                                                dto(
                                                        r,
                                                        "id",
                                                        "previous_status",
                                                        "new_status",
                                                        "changed_at",
                                                        "reason_code",
                                                        "actor_user_id"))
                                .toList());
            if (path.endsWith("/reschedule-requests")) {
                Problem.require(
                        Set.of("SCHEDULED", "CONFIRMED").contains(str(row, "status")),
                        409,
                        "INVALID_TRANSITION");
                OffsetDateTime from = OffsetDateTime.parse(text(b, "desiredStartAt")),
                        to = OffsetDateTime.parse(text(b, "desiredEndAt"));
                duration(from, to);
                UUID rid = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.reschedule_requests(tenant_id,id,appointment_id,requester_user_id,desired_start_at,desired_end_at)"
                            + " values(?,?,?,?,?,?)",
                        tenant,
                        rid,
                        id,
                        a.user(),
                        from,
                        to);
                events.audit(a, "RescheduleRequested", "rescheduleRequest", rid);
                return rescheduleDto(
                        db.one(
                                "select * from app.reschedule_requests where tenant_id=? and id=?",
                                tenant,
                                rid));
            }
            if (path.contains("/actions/")) return transition(c, row, c.tail());
            return appointmentDto(row);
        }
        if (c.isGet()) {
            boolean portal = path.contains("/portal/");
            var args = new ArrayList<Object>();
            args.add(tenant);
            var sql =
                    new StringBuilder(
                            "select ap.* from app.appointments ap join app.patients p on"
                                    + " p.tenant_id=ap.tenant_id and p.id=ap.patient_id where"
                                    + " ap.tenant_id=?");
            if (portal) {
                sql.append(" and p.portal_user_id=?");
                args.add(a.user());
            }
            if (c.query().containsKey("from")) {
                sql.append(" and ap.start_at>=?");
                args.add(OffsetDateTime.parse(c.query().get("from")));
            }
            if (c.query().containsKey("to")) {
                sql.append(" and ap.start_at<?");
                args.add(OffsetDateTime.parse(c.query().get("to")));
            }
            for (String field : List.of("professionalId", "patientId", "status", "modality"))
                if (c.query().containsKey(field)) {
                    sql.append(
                            " and ap."
                                    + switch (field) {
                                        case "professionalId" -> "professional_id::text";
                                        case "patientId" -> "patient_id::text";
                                        default -> field;
                                    }
                                    + "=?");
                    args.add(c.query().get(field));
                }
            sql.append(" order by ap.start_at,ap.id limit ? offset ?");
            args.add(c.limit() + 1);
            args.add(c.offset());
            return c.sqlPage(
                    db.rows(sql.toString(), args.toArray()).stream()
                            .map(this::appointmentDto)
                            .toList());
        }
        UUID patient = uuid(b, "patientId"), pro = uuid(b, "professionalId");
        access.patient(a, patient, true);
        if (a.has("PSYCHOLOGIST") && !a.has("OWNER", "ADMIN", "SECRETARY"))
            Problem.require(pro.equals(access.professional(a)), 404, "RESOURCE_NOT_FOUND");
        return create(
                a,
                patient,
                pro,
                OffsetDateTime.parse(text(b, "startAt")),
                OffsetDateTime.parse(text(b, "endAt")),
                text(b, "modality"),
                b.has("priceCents") ? b.get("priceCents").asLong() : null,
                null,
                null,
                null,
                "REQUESTED",
                null);
    }

    private JsonNode create(
            Actor a,
            UUID patient,
            UUID professional,
            OffsetDateTime start,
            OffsetDateTime end,
            String modality,
            Long price,
            UUID series,
            LocalDate occurrence,
            UUID old,
            String status,
            JsonNode originalPolicy) {
        duration(start, end);
        UUID tenant = a.tenant();
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,11))",
                Object.class,
                tenant + ":" + professional);
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
        Problem.require(
                db.exists(
                        "select 1 from app.care_assignments where tenant_id=? and patient_id=? and"
                            + " professional_id=? and start_at<=? and (end_at is null or end_at>?)",
                        tenant,
                        patient,
                        professional,
                        start,
                        start),
                422,
                "CARE_ASSIGNMENT_REQUIRED");
        validateAvailability(tenant, professional, start, end);
        var profile =
                parse(
                        str(
                                db.one(
                                        "select public_attributes from app.professionals where"
                                            + " tenant_id=? and id=?",
                                        tenant,
                                        professional),
                                "public_attributes"));
        if (profile.path("appointmentTypes").size() > 0) {
            boolean supported = false;
            for (JsonNode type : profile.get("appointmentTypes"))
                if (type.asText().equals(modality)) supported = true;
            Problem.require(supported, 422, "MODALITY_NOT_OFFERED");
        }
        var policy =
                originalPolicy == null
                        ? db.optional(
                                        "select payload from app.tenant_settings_versions where"
                                                + " tenant_id=? order by version desc limit 1",
                                        tenant)
                                .map(r -> parse(r.get("payload").toString()))
                                .orElse(object("minNoticeHours", 24, "cancelChargePercent", 0))
                        : originalPolicy;
        int before = policy.path("bufferBeforeMinutes").asInt(0),
                after = policy.path("bufferAfterMinutes").asInt(0);
        var tz =
                zone(
                        policy.path("defaultTimezone")
                                .asText(
                                        str(
                                                db.one(
                                                        "select timezone from app.organizations"
                                                            + " where id=?",
                                                        tenant),
                                                "timezone")));
        var day = start.atZoneSameInstant(tz).toLocalDate();
        if (policy.has("maxAppointmentsPerDay"))
            Problem.require(
                    db.jdbc.queryForObject(
                                    "select count(*) from app.appointments where tenant_id=? and"
                                        + " professional_id=? and status in"
                                        + " ('REQUESTED','SCHEDULED','CONFIRMED','IN_PROGRESS','COMPLETED','NO_SHOW')"
                                        + " and start_at>=? and start_at<?",
                                    Long.class,
                                    tenant,
                                    professional,
                                    day.atStartOfDay(tz).toOffsetDateTime(),
                                    day.plusDays(1).atStartOfDay(tz).toOffsetDateTime())
                            < policy.get("maxAppointmentsPerDay").asInt(),
                    409,
                    "DAILY_LIMIT_REACHED");
        if (price == null)
            price =
                    db.optional(
                                    "select amount_cents from app.price_rules where tenant_id=? and"
                                        + " professional_id=? and modality=? and status='ACTIVE'"
                                        + " and effective_from<=? and (effective_until is null or"
                                        + " effective_until>=?) order by effective_from desc limit"
                                        + " 1",
                                    tenant,
                                    professional,
                                    modality,
                                    start.toLocalDate(),
                                    start.toLocalDate())
                            .map(r -> ((Number) r.get("amount_cents")).longValue())
                            .orElseThrow(() -> new Problem(422, "PRICE_REQUIRED"));
        Problem.require(price >= 0 && price <= 2000000, 422, "INVALID_AMOUNT");
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.appointments(tenant_id,id,patient_id,professional_id,start_at,end_at,busy_range,modality,status,price_cents,policy_snapshot,recurrence_series_id,occurrence_date,rescheduled_from_id)"
                    + " values(?,?,?,?,?,?,tstzrange(?,?,'[)'),?,?::app.appointment_status,?,?::jsonb,?,?,?)",
                tenant,
                id,
                patient,
                professional,
                start,
                end,
                start.minusMinutes(before),
                end.plusMinutes(after),
                modality,
                status,
                price,
                policy.toString(),
                series,
                occurrence,
                old);
        history(a, id, null, status, old == null ? "CREATED" : "RESCHEDULED");
        events.emit(
                a,
                status.equals("SCHEDULED") ? "AppointmentScheduled" : "AppointmentRequested",
                "appointment",
                id,
                1);
        return appointmentDto(
                db.one("select * from app.appointments where tenant_id=? and id=?", tenant, id));
    }

    private JsonNode transition(Command c, Map<String, Object> row, String command) {
        Actor a = c.actor();
        UUID id = id(row, "id"), patient = id(row, "patient_id");
        String from = str(row, "status");
        Instant now = Instant.now(), start = instant(row, "start_at"), end = instant(row, "end_at");
        if (command.equals("confirm")) {
            Problem.require(
                    access.portal(a, patient) || access.guardian(a, patient, "SCHEDULE"),
                    404,
                    "RESOURCE_NOT_FOUND");
            if (from.equals("CONFIRMED")) return appointmentDto(row);
            Problem.require(now.isBefore(start), 409, "INVALID_TRANSITION");
        } else if (Set.of("start", "complete", "no-show").contains(command)) {
            access.requireClinical(a, patient);
            Problem.require(
                    id(row, "professional_id").equals(access.professional(a)),
                    404,
                    "RESOURCE_NOT_FOUND");
            if (command.equals("start"))
                Problem.require(
                        !now.isBefore(start.minusSeconds(900))
                                && !now.isAfter(end.plusSeconds(1800)),
                        403,
                        "VIDEO_WINDOW_CLOSED");
            if (command.equals("no-show"))
                Problem.require(
                        !now.isBefore(start.plusSeconds(900))
                                && !now.isAfter(end.plusSeconds(604800)),
                        409,
                        "NO_SHOW_WINDOW_CLOSED");
        } else if (command.equals("cancel")) {
            if (!a.has("OWNER", "ADMIN", "SECRETARY", "PSYCHOLOGIST"))
                Problem.require(
                        access.portal(a, patient) || access.guardian(a, patient, "SCHEDULE"),
                        404,
                        "RESOURCE_NOT_FOUND");
        } else a.require("OWNER", "ADMIN", "SECRETARY", "PSYCHOLOGIST");
        String target = next(from, command);
        db.exec(
                "update app.appointments set"
                    + " status=?::app.appointment_status,version=version+1,updated_at=now(),reason_code=?,confirmation_at=case"
                    + " when ?='CONFIRMED' then now() else confirmation_at end,actual_end_at=case"
                    + " when ?='COMPLETED' then now() else actual_end_at end where tenant_id=? and"
                    + " id=?",
                target,
                text(c.body(), "reasonCode"),
                target,
                target,
                a.tenant(),
                id);
        history(a, id, from, target, text(c.body(), "reasonCode"));
        events.emit(a, event(target), "appointment", id, version(row) + 1);
        return appointmentDto(
                db.one(
                        "select * from app.appointments where tenant_id=? and id=?",
                        a.tenant(),
                        id));
    }

    public static String next(String from, String command) {
        Set<String> allowed =
                switch (command) {
                    case "accept", "reject" -> Set.of("REQUESTED");
                    case "confirm" -> Set.of("SCHEDULED", "CONFIRMED");
                    case "start", "no-show" -> Set.of("SCHEDULED", "CONFIRMED");
                    case "complete" -> Set.of("IN_PROGRESS");
                    case "cancel" -> Set.of("REQUESTED", "SCHEDULED", "CONFIRMED");
                    default -> Set.of();
                };
        Problem.require(allowed.contains(from), 409, "INVALID_TRANSITION");
        return switch (command) {
            case "accept" -> "SCHEDULED";
            case "confirm" -> "CONFIRMED";
            case "start" -> "IN_PROGRESS";
            case "complete" -> "COMPLETED";
            case "no-show" -> "NO_SHOW";
            default -> "CANCELLED";
        };
    }

    private JsonNode resolveReschedule(Command c) {
        var a = c.actor();
        a.require("OWNER", "ADMIN", "SECRETARY", "PSYCHOLOGIST");
        UUID tenant = a.tenant(), rid = c.id("rescheduleId");
        var request =
                db.one(
                        "select * from app.reschedule_requests where tenant_id=? and id=? for"
                                + " update",
                        tenant,
                        rid);
        var old =
                db.one(
                        "select * from app.appointments where tenant_id=? and id=? for update",
                        tenant,
                        request.get("appointment_id"));
        access.appointment(a, old, true);
        Problem.require(
                str(request, "status").equals("PENDING")
                        && Set.of("SCHEDULED", "CONFIRMED").contains(str(old, "status")),
                409,
                "INVALID_TRANSITION");
        UUID newId = null;
        String target = c.tail().equals("accept") ? "ACCEPTED" : "DECLINED";
        if (target.equals("ACCEPTED")) {
            db.exec(
                    "update app.appointments set"
                        + " status='CANCELLED',reason_code='RESCHEDULED',version=version+1,updated_at=now()"
                        + " where tenant_id=? and id=?",
                    tenant,
                    old.get("id"));
            JsonNode created =
                    create(
                            a,
                            id(old, "patient_id"),
                            id(old, "professional_id"),
                            offset(request, "desired_start_at"),
                            offset(request, "desired_end_at"),
                            str(old, "modality"),
                            ((Number) old.get("price_cents")).longValue(),
                            old.get("recurrence_series_id") == null
                                    ? null
                                    : id(old, "recurrence_series_id"),
                            old.get("occurrence_date") == null
                                    ? null
                                    : LocalDate.parse(old.get("occurrence_date").toString()),
                            id(old, "id"),
                            "SCHEDULED",
                            parse(old.get("policy_snapshot").toString()));
            newId = uuid(created, "id");
            history(a, id(old, "id"), str(old, "status"), "CANCELLED", "RESCHEDULED");
            events.emit(a, "AppointmentRescheduled", "appointment", newId, 1);
        }
        db.exec(
                "update app.reschedule_requests set"
                    + " status=?::app.reschedule_status,new_appointment_id=?,resolved_at=now(),version=version+1"
                    + " where tenant_id=? and id=?",
                target,
                newId,
                tenant,
                rid);
        return rescheduleDto(
                db.one(
                        "select * from app.reschedule_requests where tenant_id=? and id=?",
                        tenant,
                        rid));
    }

    private JsonNode recurrence(Command c) {
        var a = c.actor();
        var b = c.body();
        UUID patient = uuid(b, "patientId"), pro = uuid(b, "professionalId");
        access.patient(a, patient, true);
        String rrule = text(b, "rrule");
        Problem.require(
                rrule.matches(
                        "FREQ=(WEEKLY|MONTHLY)(;INTERVAL=[12])?(;COUNT=([1-9]|[1-4][0-9]|5[0-2]))?"),
                422,
                "INVALID_RECURRENCE");
        Problem.require(
                b.has("maxCount") || b.has("endsOn") || rrule.contains("COUNT="),
                422,
                "RECURRENCE_END_REQUIRED");
        int count =
                b.path("maxCount")
                        .asInt(
                                rrule.contains("COUNT=")
                                        ? Integer.parseInt(
                                                rrule.substring(rrule.indexOf("COUNT=") + 6))
                                        : 52);
        UUID id = UUID.randomUUID();
        LocalDate starts = LocalDate.parse(text(b, "startsOn"));
        int minutes = b.path("durationMinutes").asInt(50);
        Problem.require(minutes % 5 == 0, 422, "INVALID_DURATION");
        db.exec(
                "insert into"
                    + " app.recurrence_series(tenant_id,id,patient_id,professional_id,rrule,local_start_time,timezone,starts_on,ends_on,max_count,duration_minutes,modality,price_cents)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.tenant(),
                id,
                patient,
                pro,
                rrule,
                time(text(b, "localStartTime")),
                text(b, "timezone"),
                starts,
                b.has("endsOn") ? LocalDate.parse(text(b, "endsOn")) : null,
                count,
                minutes,
                b.path("modality").asText("REMOTE"),
                b.path("priceCents").asLong(0));
        expandSeries(a, id);
        return object("id", id, "version", 1);
    }

    public void expandSeries(Actor a, UUID seriesId) {
        var s =
                db.one(
                        "select * from app.recurrence_series where tenant_id=? and id=? for update",
                        a.tenant(),
                        seriesId);
        if (!str(s, "status").equals("ACTIVE")) return;
        LocalDate date = LocalDate.parse(s.get("starts_on").toString()),
                limit = LocalDate.now(zone(str(s, "timezone"))).plusDays(90);
        String rule = str(s, "rrule");
        int interval = rule.contains("INTERVAL=2") ? 2 : 1;
        int count = ((Number) s.get("max_count")).intValue();
        for (int i = 0; i < count; i++) {
            LocalDate next =
                    rule.startsWith("FREQ=MONTHLY")
                            ? monthDate(date, i * interval)
                            : date.plusWeeks((long) i * interval);
            if (next.isAfter(limit)
                    || (s.get("ends_on") != null
                            && next.isAfter(LocalDate.parse(s.get("ends_on").toString())))) break;
            if (db.exists(
                    "select 1 from app.appointments where tenant_id=? and recurrence_series_id=?"
                            + " and occurrence_date=? and rescheduled_from_id is null",
                    a.tenant(),
                    seriesId,
                    next)) continue;
            OffsetDateTime start =
                    resolveLocal(
                            LocalDateTime.of(next, time(s.get("local_start_time").toString())),
                            zone(str(s, "timezone")),
                            null);
            create(
                    a,
                    id(s, "patient_id"),
                    id(s, "professional_id"),
                    start,
                    start.plusMinutes(((Number) s.get("duration_minutes")).intValue()),
                    str(s, "modality"),
                    ((Number) s.get("price_cents")).longValue(),
                    seriesId,
                    next,
                    null,
                    "SCHEDULED",
                    null);
        }
        db.exec(
                "update app.recurrence_series set expanded_until=? where tenant_id=? and id=?",
                limit,
                a.tenant(),
                seriesId);
    }

    private LocalDate monthDate(LocalDate d, int months) {
        var month = YearMonth.from(d).plusMonths(months);
        Problem.require(d.getDayOfMonth() <= month.lengthOfMonth(), 422, "INVALID_LOCAL_TIME");
        return month.atDay(d.getDayOfMonth());
    }

    public static OffsetDateTime resolveLocal(
            LocalDateTime local, ZoneId zone, ZoneOffset selected) {
        var offsets = zone.getRules().getValidOffsets(local);
        Problem.require(
                !offsets.isEmpty()
                        && (offsets.size() == 1 || selected != null && offsets.contains(selected)),
                422,
                "INVALID_LOCAL_TIME");
        return OffsetDateTime.of(local, selected == null ? offsets.getFirst() : selected);
    }

    public static void duration(OffsetDateTime start, OffsetDateTime end) {
        long seconds = Duration.between(start, end).getSeconds();
        Problem.require(
                seconds >= 1800 && seconds <= 10800 && seconds % 300 == 0, 422, "INVALID_DURATION");
    }

    private void validateAvailability(
            UUID tenant, UUID pro, OffsetDateTime start, OffsetDateTime end) {
        var policy =
                db.optional(
                                "select payload from app.tenant_settings_versions where tenant_id=?"
                                    + " order by version desc limit 1",
                                tenant)
                        .map(r -> parse(r.get("payload").toString()))
                        .orElse(object());
        var tz =
                zone(
                        policy.path("defaultTimezone")
                                .asText(
                                        str(
                                                db.one(
                                                        "select timezone from app.organizations"
                                                            + " where id=?",
                                                        tenant),
                                                "timezone")));
        var localStart = start.atZoneSameInstant(tz);
        var localEnd = end.atZoneSameInstant(tz);
        for (JsonNode holiday : policy.path("holidays"))
            Problem.require(
                    !holiday.asText().equals(localStart.toLocalDate().toString()),
                    409,
                    "HOLIDAY_CLOSED");
        if (policy.path("openingHours").size() > 0) {
            boolean open = false;
            for (JsonNode hours : policy.get("openingHours"))
                if (hours.path("weekday").asInt() == localStart.getDayOfWeek().getValue()
                        && localStart.toLocalDate().equals(localEnd.toLocalDate())
                        && !localStart.toLocalTime().isBefore(LocalTime.parse(text(hours, "start")))
                        && !localEnd.toLocalTime().isAfter(LocalTime.parse(text(hours, "end"))))
                    open = true;
            Problem.require(open, 409, "ORGANIZATION_CLOSED");
        }
        boolean found = false;
        for (var r :
                db.rows(
                        "select * from app.availability_rules where tenant_id=? and"
                                + " professional_id=?",
                        tenant,
                        pro)) {
            var local = start.atZoneSameInstant(zone(str(r, "timezone")));
            var finish = end.atZoneSameInstant(zone(str(r, "timezone")));
            LocalDate day = local.toLocalDate();
            if (day.getDayOfWeek().getValue() == ((Number) r.get("weekday")).intValue()
                    && !day.isBefore(LocalDate.parse(r.get("effective_from").toString()))
                    && (r.get("effective_until") == null
                            || !day.isAfter(LocalDate.parse(r.get("effective_until").toString())))
                    && day.equals(finish.toLocalDate())
                    && !local.toLocalTime().isBefore(time(str(r, "local_start")))
                    && !finish.toLocalTime().isAfter(time(str(r, "local_end")))) found = true;
        }
        Problem.require(found, 409, "SLOT_UNAVAILABLE");
        Problem.require(
                !db.exists(
                        "select 1 from app.availability_exceptions where tenant_id=? and"
                                + " professional_id=? and from_at<? and to_at>?",
                        tenant,
                        pro,
                        end,
                        start),
                409,
                "SLOT_UNAVAILABLE");
    }

    private JsonNode publicBooking(Command c) {
        var b = c.body();
        UUID pro = uuid(b, "professionalId");
        var p =
                db.one(
                        "select p.tenant_id from app.professionals p join app.organizations o on"
                            + " o.id=p.tenant_id where p.id=? and p.verification_status='VERIFIED'"
                            + " and p.public_profile_enabled and o.status='ACTIVE'",
                        pro);
        UUID tenant = id(p, "tenant_id");
        context(tenant);
        Problem.require(!text(b, "noticeVersion").isBlank(), 422, "CONSENT_REQUIRED");
        OffsetDateTime start = OffsetDateTime.parse(text(b, "startAt")),
                end = OffsetDateTime.parse(text(b, "endAt"));
        duration(start, end);
        Problem.require(start.isAfter(OffsetDateTime.now()), 422, "INVALID_LOCAL_TIME");
        validateAvailability(tenant, pro, start, end);
        byte[]
                fingerprint =
                        crypto.lookup(
                                tenant,
                                text(b, "patientEmail") + ":" + pro + ":" + start.toInstant()),
                bodyHash = hash(canonical(b));
        db.exec(
                "update app.public_booking_requests set status='EXPIRED' where tenant_id=? and"
                        + " request_fingerprint_hmac=? and status in ('NEW','CONTACTED') and"
                        + " expires_at<=now()",
                tenant,
                fingerprint);
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,5))",
                Object.class,
                tenant + ":" + HexFormat.of().formatHex(fingerprint));
        var prior =
                db.optional(
                        "select * from app.public_idempotency where tenant_id=? and fingerprint=?"
                                + " and route_key='booking' and idem_key=? and expires_at>now()",
                        tenant,
                        fingerprint,
                        c.idempotencyKey());
        if (prior.isPresent()) {
            Problem.require(
                    MessageDigest.isEqual(bodyHash, (byte[]) prior.get().get("body_hash")),
                    409,
                    "IDEMPOTENCY_MISMATCH");
            return object("id", prior.get().get("result_id"), "version", 1);
        }
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.public_booking_requests(tenant_id,id,professional_id,patient_name_cipher,patient_email_cipher,requested_start_at,requested_end_at,request_fingerprint_hmac,expires_at)"
                    + " values(?,?,?,?,?,?,?,?,now()+interval '72 hours')",
                tenant,
                id,
                pro,
                crypto.encrypt(
                        tenant, "public_booking_requests.name", id, 1, text(b, "patientName")),
                crypto.encrypt(
                        tenant, "public_booking_requests.email", id, 1, text(b, "patientEmail")),
                start,
                end,
                fingerprint);
        db.exec(
                "insert into"
                    + " app.public_idempotency(tenant_id,fingerprint,route_key,idem_key,body_hash,result_id)"
                    + " values(?,?,'booking',?,?,?) on"
                    + " conflict(tenant_id,fingerprint,route_key,idem_key) do update set"
                    + " body_hash=excluded.body_hash,result_id=excluded.result_id,expires_at=now()+interval"
                    + " '72 hours'",
                tenant,
                fingerprint,
                c.idempotencyKey(),
                bodyHash,
                id);
        return object("id", id, "version", 1);
    }

    private JsonNode bookingInbox(Command c) {
        Actor a = c.actor();
        a.require("OWNER", "ADMIN", "SECRETARY", "PSYCHOLOGIST");
        UUID tenant = a.tenant();
        if (c.isGet())
            return c.page(
                    db
                            .rows(
                                    "select * from app.public_booking_requests where tenant_id=?"
                                        + " and status in ('NEW','CONTACTED') and expires_at>now()"
                                        + " order by created_at",
                                    tenant)
                            .stream()
                            .filter(
                                    r ->
                                            !a.has("PSYCHOLOGIST")
                                                    || a.has("OWNER", "ADMIN", "SECRETARY")
                                                    || id(r, "professional_id")
                                                            .equals(access.professional(a)))
                            .map(
                                    r ->
                                            object(
                                                    "id",
                                                    r.get("id"),
                                                    "status",
                                                    r.get("status"),
                                                    "professionalId",
                                                    r.get("professional_id"),
                                                    "patientName",
                                                    crypto.decrypt(
                                                            tenant,
                                                            "public_booking_requests.name",
                                                            id(r, "id"),
                                                            1,
                                                            (byte[]) r.get("patient_name_cipher")),
                                                    "patientEmail",
                                                    crypto.decrypt(
                                                            tenant,
                                                            "public_booking_requests.email",
                                                            id(r, "id"),
                                                            1,
                                                            (byte[]) r.get("patient_email_cipher")),
                                                    "startAt",
                                                    instant(r, "requested_start_at").toString(),
                                                    "endAt",
                                                    instant(r, "requested_end_at").toString(),
                                                    "version",
                                                    1))
                            .toList());
        UUID id = c.id("requestId");
        var lead =
                db.one(
                        "select * from app.public_booking_requests where tenant_id=? and id=? and"
                                + " status in ('NEW','CONTACTED') and expires_at>now() for update",
                        tenant,
                        id);
        if (a.has("PSYCHOLOGIST") && !a.has("OWNER", "ADMIN", "SECRETARY"))
            Problem.require(
                    id(lead, "professional_id").equals(access.professional(a)),
                    404,
                    "RESOURCE_NOT_FOUND");
        if (c.tail().equals("reject")) {
            db.exec(
                    "update app.public_booking_requests set status='REJECTED' where tenant_id=? and"
                            + " id=?",
                    tenant,
                    id);
            events.audit(a, "BookingRequestRejected", "bookingRequest", id);
            return object("id", id, "version", 1);
        }
        String
                name =
                        crypto.decrypt(
                                tenant,
                                "public_booking_requests.name",
                                id,
                                1,
                                (byte[]) lead.get("patient_name_cipher")),
                email =
                        crypto.decrypt(
                                tenant,
                                "public_booking_requests.email",
                                id,
                                1,
                                (byte[]) lead.get("patient_email_cipher"));
        UUID patient = enrollment.enroll(a, name, email, id(lead, "professional_id"));
        JsonNode created =
                create(
                        a,
                        patient,
                        id(lead, "professional_id"),
                        offset(lead, "requested_start_at"),
                        offset(lead, "requested_end_at"),
                        "REMOTE",
                        null,
                        null,
                        null,
                        null,
                        "SCHEDULED",
                        null);
        db.exec(
                "update app.public_booking_requests set"
                    + " status='ACCEPTED',patient_id=?,appointment_id=? where tenant_id=? and id=?",
                patient,
                uuid(created, "id"),
                tenant,
                id);
        events.audit(a, "BookingRequestAccepted", "bookingRequest", id);
        return created;
    }

    private JsonNode confirmToken(Command c) {
        byte[] tokenHash = hash(text(c.body(), "token"));
        UUID tenant =
                id(
                        db.one(
                                "select tenant_id from app.action_routes where token_hash=?",
                                tokenHash),
                        "tenant_id");
        context(tenant);
        var token =
                db.one(
                        "select * from app.action_tokens where tenant_id=? and token_hash=? and"
                                + " purpose='APPOINTMENT_CONFIRM' and expires_at>now() for update",
                        tenant,
                        tokenHash);
        UUID id = id(token, "appointment_id");
        if (token.get("consumed_at") != null) return object("id", id, "version", 1);
        db.jdbc.queryForObject(
                "select set_config('app.action_appointment_id',?,true)",
                String.class,
                id.toString());
        var ap =
                db.one(
                        "select * from app.appointments where tenant_id=? and id=? for update",
                        tenant,
                        id);
        Problem.require(instant(ap, "start_at").isAfter(Instant.now()), 409, "INVALID_TRANSITION");
        String from = str(ap, "status");
        if (!from.equals("CONFIRMED")) {
            next(from, "confirm");
            db.exec(
                    "update app.appointments set"
                            + " status='CONFIRMED',confirmation_at=now(),version=version+1 where"
                            + " tenant_id=? and id=?",
                    tenant,
                    id);
            var actor = new Actor(tenant, null, Set.of(), null, c.actor().requestId());
            history(actor, id, from, "CONFIRMED", "ACTION_LINK");
            events.emit(actor, "AppointmentConfirmed", "appointment", id, version(ap) + 1);
        }
        db.exec(
                "update app.action_tokens set consumed_at=now() where tenant_id=? and id=?",
                tenant,
                token.get("id"));
        return object("id", id, "version", version(ap) + 1);
    }

    private void context(UUID t) {
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, t.toString());
    }

    private void history(Actor a, UUID id, String from, String to, String reason) {
        db.exec(
                "insert into"
                    + " app.appointment_history(tenant_id,appointment_id,previous_status,new_status,actor_user_id,reason_code,request_id)"
                    + " values(?,?,?::app.appointment_status,?::app.appointment_status,?,?,?)",
                a.tenant(),
                id,
                from,
                to,
                a.user(),
                reason,
                a.requestId());
    }

    private String event(String status) {
        return switch (status) {
            case "CONFIRMED" -> "AppointmentConfirmed";
            case "IN_PROGRESS" -> "AppointmentStarted";
            case "COMPLETED" -> "AppointmentCompleted";
            case "NO_SHOW" -> "AppointmentNoShow";
            case "CANCELLED" -> "AppointmentCancelled";
            default -> "AppointmentScheduled";
        };
    }

    public static Instant instant(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v instanceof java.sql.Timestamp ts
                ? ts.toInstant()
                : v instanceof OffsetDateTime o ? o.toInstant() : Instant.parse(v.toString());
    }

    private static OffsetDateTime offset(Map<String, Object> r, String key) {
        return instant(r, key).atOffset(ZoneOffset.UTC);
    }

    private static ZoneId zone(String s) {
        try {
            return ZoneId.of(s);
        } catch (Exception e) {
            throw new Problem(422, "INVALID_TIMEZONE");
        }
    }

    private static LocalTime time(String s) {
        try {
            return LocalTime.parse(s);
        } catch (Exception e) {
            throw new Problem(422, "INVALID_LOCAL_TIME");
        }
    }

    private JsonNode appointmentDto(Map<String, Object> r) {
        return dto(
                r,
                "id",
                "patient_id",
                "professional_id",
                "start_at",
                "end_at",
                "status",
                "version",
                "modality",
                "rescheduled_from_id",
                "price_cents");
    }

    private JsonNode rescheduleDto(Map<String, Object> r) {
        return dto(
                r,
                "id",
                "appointment_id",
                "status",
                "desired_start_at",
                "desired_end_at",
                "new_appointment_id",
                "version");
    }
}
