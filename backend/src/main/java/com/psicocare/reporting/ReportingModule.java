package com.psicocare.reporting;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class ReportingModule implements ModuleHandler {
    private final Store db;
    private final Events events;

    public ReportingModule(Store db, Events events) {
        this.db = db;
        this.events = events;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Reporting") || c.tag().equals("Audit");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        String path = c.path();
        if (c.tag().equals("Audit")) {
            a.require("OWNER", "ADMIN");
            return c.page(
                    db
                            .rows(
                                    "select * from app.audit_events where tenant_id=? order by"
                                            + " occurred_at desc",
                                    tenant)
                            .stream()
                            .map(
                                    r ->
                                            dto(
                                                    r,
                                                    "id",
                                                    "occurred_at",
                                                    "actor_user_id",
                                                    "action_code",
                                                    "entity_type",
                                                    "entity_id",
                                                    "correlation_id",
                                                    "success",
                                                    "reason_code"))
                            .toList());
        }
        if (path.endsWith("/dashboard")) {
            a.require("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY", "BILLING");
            String tz =
                    db.one("select timezone from app.organizations where id=?", tenant)
                            .get("timezone")
                            .toString();
            LocalDate day = LocalDate.now(ZoneId.of(tz));
            var stats = aggregate(tenant, day, tz);
            if (!a.has("OWNER", "ADMIN", "BILLING"))
                ((tools.jackson.databind.node.ObjectNode) stats).remove("openAmountCents");
            return page(List.of(stats));
        }
        if (path.endsWith("/regenerate")) {
            a.require("OWNER", "ADMIN");
            String tz =
                    db.one("select timezone from app.organizations where id=?", tenant)
                            .get("timezone")
                            .toString();
            JsonNode result = generate(a, LocalDate.now(ZoneId.of(tz)), tz);
            events.audit(a, "DailyReportRegenerated", "dailyReport", uuid(result, "id"));
            return result;
        }
        a.require("OWNER", "ADMIN", "SECRETARY", "BILLING");
        if (c.vars().containsKey("reportId"))
            return report(
                    db.one(
                            "select * from app.daily_reports where tenant_id=? and id=?",
                            tenant,
                            c.id("reportId")));
        return c.page(
                db
                        .rows(
                                "select * from app.daily_reports where tenant_id=? order by"
                                        + " local_date desc",
                                tenant)
                        .stream()
                        .map(this::report)
                        .toList());
    }

    public JsonNode generate(Actor a, LocalDate day, String timezone) {
        db.exec(
                "insert into app.daily_reports(tenant_id,local_date,timezone,status)"
                    + " values(?,?,?,'PENDING') on conflict(tenant_id,local_date,report_type) do"
                    + " nothing",
                a.tenant(),
                day,
                timezone);
        var r =
                db.one(
                        "select * from app.daily_reports where tenant_id=? and local_date=? and"
                                + " report_type='DAILY_AGENDA' for update",
                        a.tenant(),
                        day);
        db.exec(
                "update app.daily_reports set"
                        + " aggregate_json=?::jsonb,status='GENERATED',generated_at=now() where"
                        + " tenant_id=? and id=?",
                aggregate(a.tenant(), day, timezone).toString(),
                a.tenant(),
                r.get("id"));
        return report(
                db.one(
                        "select * from app.daily_reports where tenant_id=? and id=?",
                        a.tenant(),
                        r.get("id")));
    }

    private JsonNode aggregate(UUID tenant, LocalDate day, String timezone) {
        var zone = ZoneId.of(timezone);
        var from = day.atStartOfDay(zone).toOffsetDateTime();
        var to = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var status =
                db
                        .rows(
                                "select status,count(*) as count from app.appointments where"
                                    + " tenant_id=? and start_at>=? and start_at<? group by status",
                                tenant,
                                from,
                                to)
                        .stream()
                        .map(r -> dto(r, "status", "count"))
                        .toList();
        long open =
                db.jdbc.queryForObject(
                        "select coalesce(sum(amount_cents-paid_cents),0) from app.invoices where"
                                + " tenant_id=? and status in ('OPEN','OVERDUE','PARTIALLY_PAID')",
                        Long.class,
                        tenant);
        long pending =
                db.jdbc.queryForObject(
                        "select count(*) from app.reschedule_requests where tenant_id=? and"
                                + " status='PENDING'",
                        Long.class,
                        tenant);
        var previousFrom = day.minusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var changes =
                db
                        .rows(
                                "select new_status as status,count(*) as count from"
                                    + " app.appointment_history where tenant_id=? and changed_at>=?"
                                    + " and changed_at<? group by new_status",
                                tenant,
                                previousFrom,
                                from)
                        .stream()
                        .map(r -> dto(r, "status", "count"))
                        .toList();
        return object(
                "localDate",
                day.toString(),
                "timezone",
                timezone,
                "previousDay",
                day.minusDays(1).toString(),
                "previousDayChanges",
                changes,
                "todayStatus",
                status,
                "pendingReschedules",
                pending,
                "openAmountCents",
                open);
    }

    private JsonNode report(Map<String, Object> r) {
        return object(
                "id",
                r.get("id"),
                "localDate",
                r.get("local_date").toString(),
                "status",
                r.get("status"),
                "generatedAt",
                r.get("generated_at") == null ? null : r.get("generated_at").toString(),
                "aggregate",
                r.get("aggregate_json") == null
                        ? object()
                        : parse(r.get("aggregate_json").toString()));
    }
}
