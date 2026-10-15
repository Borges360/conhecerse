package com.psicocare.worker;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.billing.BillingModule;
import com.psicocare.communications.CommunicationGateway;
import com.psicocare.reporting.ReportingModule;
import com.psicocare.scheduling.SchedulingModule;
import com.psicocare.shared.*;
import com.psicocare.video.VideoModule;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Component
public class WorkerEngine {
    record Tenant(UUID id, UUID actor, String timezone) {}

    record Task(Tenant tenant, UUID id, String table, JsonNode payload, int attempts) {}

    private final Store db;
    private final TransactionTemplate tx;
    private final Crypto crypto;
    private final PaymentProvider payments;
    private final BillingModule billing;
    private final com.psicocare.billing.SubscriptionsModule subscriptions;
    private final SchedulingModule scheduling;
    private final ReportingModule reporting;
    private final VideoModule video;
    private final CommunicationGateway comms;
    private final Events events;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final String publicUrl;

    public WorkerEngine(
            Store db,
            TransactionTemplate tx,
            Crypto crypto,
            PaymentProvider payments,
            BillingModule billing,
            com.psicocare.billing.SubscriptionsModule subscriptions,
            SchedulingModule scheduling,
            ReportingModule reporting,
            CommunicationGateway comms,
            VideoModule video,
            Events events,
            MeterRegistry metrics,
            @Value("${app.worker}") boolean enabled,
            @Value("${app.public-url}") String publicUrl) {
        this.db = db;
        this.tx = tx;
        this.crypto = crypto;
        this.payments = payments;
        this.billing = billing;
        this.subscriptions = subscriptions;
        this.scheduling = scheduling;
        this.reporting = reporting;
        this.comms = comms;
        this.video = video;
        this.events = events;
        this.metrics = metrics;
        this.enabled = enabled;
        this.publicUrl = publicUrl;
    }

    @Scheduled(fixedDelayString = "${WORKER_POLL_MS:2000}")
    public void tick() {
        if (!enabled) return;
        for (var row :
                db.rows(
                        "select id,worker_user_id,timezone from app.organizations where"
                                + " status='ACTIVE' and worker_user_id is not null")) {
            Tenant t = new Tenant(id(row, "id"), id(row, "worker_user_id"), str(row, "timezone"));
            try {
                schedule(t);
                try {
                    in(
                            t,
                            () -> {
                                video.sweep(actor(t));
                                return null;
                            });
                } catch (Exception unavailable) {
                    metrics.counter("conhecerse_video_failures").increment();
                }
                for (int batch = 0; batch < 100; batch++) {
                    if (!pending(t)) break;
                    dispatch(t);
                    processJob(t);
                    processWebhook(t);
                    sendNotification(t);
                }
            } catch (Exception e) {
                metrics.counter("conhecerse_worker_failures").increment();
            }
        }
    }

    private boolean pending(Tenant t) {
        return in(
                t,
                () ->
                        db.exists(
                                "select 1 from app.jobs where tenant_id=? and (status='PENDING' or"
                                    + " status='PROCESSING' and locked_until<now()) and"
                                    + " run_after<=now() union all select 1 from app.outbox_events"
                                    + " where tenant_id=? and (status='PENDING' or"
                                    + " status='PROCESSING' and locked_until<now()) and"
                                    + " next_attempt_at<=now() union all select 1 from"
                                    + " app.inbox_events where tenant_id=? and (status='PENDING' or"
                                    + " status='PROCESSING' and locked_until<now()) and"
                                    + " next_attempt_at<=now() union all select 1 from"
                                    + " app.notifications where tenant_id=? and status='QUEUED' and"
                                    + " due_at<=now() and (locked_until is null or"
                                    + " locked_until<now()) limit 1",
                                t.id(),
                                t.id(),
                                t.id(),
                                t.id()));
    }

    private <T> T in(Tenant t, java.util.function.Supplier<T> work) {
        return tx.execute(
                status -> {
                    context(t);
                    return work.get();
                });
    }

    private void context(Tenant t) {
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, t.id().toString());
        db.jdbc.queryForObject(
                "select set_config('app.actor_id',?,true)", String.class, t.actor().toString());
    }

    private Actor actor(Tenant t) {
        return new Actor(
                t.id(), t.actor(), Set.of("SECRETARY", "BILLING"), null, UUID.randomUUID());
    }

    private void schedule(Tenant t) {
        in(
                t,
                () -> {
                    var now = ZonedDateTime.now(ZoneId.of(t.timezone()));
                    String day = now.toLocalDate().toString();
                    if (now.getHour() >= 7) job(t, "DAILY_REPORT", day, object("localDate", day));
                    if (now.getHour() >= 2)
                        for (var r :
                                db.rows(
                                        "select id from app.recurrence_series where tenant_id=? and"
                                                + " status='ACTIVE' and (expanded_until is null or"
                                                + " expanded_until<current_date+89)",
                                        t.id()))
                            job(
                                    t,
                                    "RECURRENCE",
                                    r.get("id") + ":" + day,
                                    object("seriesId", r.get("id")));
                    String period = now.toLocalDate() + ":" + now.getHour() / 6;
                    for (var s :
                            db.rows(
                                    "select id from app.platform_subscriptions where tenant_id=?"
                                        + " and status<>'CANCELLED'",
                                    t.id()))
                        job(
                                t,
                                "PLATFORM_SUBSCRIPTION",
                                s.get("id") + ":" + period,
                                object("subscriptionId", s.get("id")));
                    for (var r :
                            db.rows(
                                    "select id from app.payments where tenant_id=? and"
                                        + " provider='ASAAS' and provider_payment_ref is not null"
                                        + " and status in ('PENDING','AUTHORIZED','CONFIRMED')",
                                    t.id()))
                        job(
                                t,
                                "PAYMENT_RECONCILE",
                                r.get("id") + ":" + period,
                                object("paymentId", r.get("id")));
                    for (var ap :
                            db.rows(
                                    "select id,start_at from app.appointments where tenant_id=? and"
                                        + " status in ('SCHEDULED','CONFIRMED') and start_at>now()"
                                        + " and start_at<now()+interval '49 hours'",
                                    t.id()))
                        for (int hours : List.of(48, 24)) {
                            UUID apId = id(ap, "id");
                            OffsetDateTime due =
                                    ((java.sql.Timestamp) ap.get("start_at"))
                                            .toInstant()
                                            .minusSeconds(hours * 3600L)
                                            .atOffset(ZoneOffset.UTC);
                            db.exec(
                                    "insert into"
                                        + " app.jobs(tenant_id,job_type,natural_key,payload,run_after)"
                                        + " values(?,'APPOINTMENT_REMINDER',?,?::jsonb,?) on"
                                        + " conflict(tenant_id,job_type,natural_key) do nothing",
                                    t.id(),
                                    apId + ":" + hours,
                                    object("appointmentId", apId).toString(),
                                    due);
                        }
                    db.exec(
                            "update app.invoices set status='OVERDUE',version=version+1 where"
                                    + " tenant_id=? and status in ('OPEN','PARTIALLY_PAID') and"
                                    + " due_on<?",
                            t.id(),
                            now.toLocalDate());
                    db.exec(
                            "update app.reschedule_requests set"
                                + " status='EXPIRED',resolved_at=now(),version=version+1 where"
                                + " tenant_id=? and status='PENDING' and desired_start_at<now()",
                            t.id());
                    db.exec(
                            "update app.public_booking_requests set status='EXPIRED' where"
                                    + " tenant_id=? and status in ('NEW','CONTACTED') and"
                                    + " expires_at<=now()",
                            t.id());
                    return null;
                });
    }

    private void job(Tenant t, String type, String key, JsonNode payload) {
        db.exec(
                "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                        + " values(?,?,?,?::jsonb) on conflict(tenant_id,job_type,natural_key) do"
                        + " nothing",
                t.id(),
                type,
                key,
                payload.toString());
    }

    private Task claim(Tenant t, String table) {
        return in(
                t,
                () -> {
                    String ready = table.equals("jobs") ? "run_after" : "next_attempt_at";
                    var row =
                            db.optional(
                                    "select * from app."
                                            + table
                                            + " where tenant_id=? and (status='PENDING' or"
                                            + " status='PROCESSING' and locked_until<now()) and "
                                            + ready
                                            + "<=now() order by "
                                            + ready
                                            + " for update skip locked limit 1",
                                    t.id());
                    if (row.isEmpty()) return null;
                    var r = row.get();
                    db.exec(
                            "update app."
                                    + table
                                    + " set status='PROCESSING',attempts=attempts+1,locked_until=now()+interval"
                                    + " '2 minutes' where tenant_id=? and id=?",
                            t.id(),
                            r.get("id"));
                    return new Task(
                            t,
                            id(r, "id"),
                            table,
                            object(
                                    "row",
                                    r.get("payload") == null
                                            ? null
                                            : parse(r.get("payload").toString()),
                                    "type",
                                    r.get(table.equals("jobs") ? "job_type" : "event_type"),
                                    "aggregateId",
                                    r.get("aggregate_id"),
                                    "eventVersion",
                                    r.get("event_version")),
                            ((Number) r.get("attempts")).intValue() + 1);
                });
    }

    private void finish(Task task) {
        in(
                task.tenant(),
                () -> {
                    db.exec(
                            "update app."
                                    + task.table()
                                    + " set status='DONE',locked_until=null where tenant_id=? and"
                                    + " id=?",
                            task.tenant().id(),
                            task.id());
                    return null;
                });
    }

    private void retry(Task task, Exception error) {
        in(
                task.tenant(),
                () -> {
                    String ready = task.table().equals("jobs") ? "run_after" : "next_attempt_at";
                    boolean dead =
                            task.attempts() >= (task.table().equals("outbox_events") ? 10 : 5)
                                    || error instanceof Problem p
                                            && p.code.equals("UNSUPPORTED_EVENT_VERSION");
                    int delay =
                            Math.min(21600, (1 << Math.min(10, task.attempts())) * 30)
                                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(30);
                    db.exec(
                            "update app."
                                    + task.table()
                                    + " set status=?,locked_until=null,"
                                    + ready
                                    + "=now()+make_interval(secs=>?) where tenant_id=? and id=?",
                            dead ? "DLQ" : "PENDING",
                            delay,
                            task.tenant().id(),
                            task.id());
                    if (dead)
                        metrics.counter("conhecerse_dlq_total", "queue", task.table()).increment();
                    return null;
                });
    }

    private void dispatch(Tenant t) {
        Task task = claim(t, "outbox_events");
        if (task == null) return;
        try {
            requireEventVersion(task.payload().path("eventVersion").asInt());
            in(
                    t,
                    () -> {
                        if (db.exists(
                                "select 1 from app.consumed_events where tenant_id=? and"
                                        + " consumer_name='core' and event_id=?",
                                t.id(),
                                task.id())) return null;
                        String type = task.payload().path("type").asText();
                        UUID aggregate = uuid(task.payload(), "aggregateId");
                        if (type.equals("AppointmentCompleted")) invoiceCompleted(t, aggregate);
                        if (type.equals("AppointmentScheduled"))
                            job(
                                    t,
                                    "VIDEO_PROVISION",
                                    aggregate.toString(),
                                    object("appointmentId", aggregate));
                        UUID patient = null;
                        if (type.startsWith("Appointment"))
                            patient =
                                    db.optional(
                                                    "select patient_id from app.appointments where"
                                                            + " tenant_id=? and id=?",
                                                    t.id(),
                                                    aggregate)
                                            .map(r -> id(r, "patient_id"))
                                            .orElse(null);
                        if (type.equals("DocumentIssued"))
                            patient =
                                    db.optional(
                                                    "select patient_id from app.documents where"
                                                            + " tenant_id=? and id=?",
                                                    t.id(),
                                                    aggregate)
                                            .map(r -> id(r, "patient_id"))
                                            .orElse(null);
                        if (task.payload().path("row").has("patientId"))
                            patient = uuid(task.payload().path("row"), "patientId");
                        if (type.equals("InvoiceIssued") || type.equals("PaymentStateUpdated"))
                            patient =
                                    db.optional(
                                                    "select ip.patient_id from app.invoices i join"
                                                        + " app.invoice_payers ip on"
                                                        + " ip.tenant_id=i.tenant_id and"
                                                        + " ip.id=i.payer_id where i.tenant_id=?"
                                                        + " and i.id=? and ip.patient_id is not"
                                                        + " null",
                                                    t.id(),
                                                    aggregate)
                                            .map(r -> id(r, "patient_id"))
                                            .orElse(null);
                        if (type.equals("ContentPUBLISHED"))
                            for (var subscription :
                                    db.rows(
                                            "select ns.id from app.newsletter_subscriptions ns join"
                                                + " app.content_entries ce on"
                                                + " ce.tenant_id=ns.tenant_id where ns.tenant_id=?"
                                                + " and ce.id=? and ce.entry_type='NEWSLETTER' and"
                                                + " ns.unsubscribed_at is null and ns.verified_at"
                                                + " is not null",
                                            t.id(),
                                            aggregate))
                                job(
                                        t,
                                        "NEWSLETTER_SEND",
                                        aggregate + ":" + subscription.get("id"),
                                        object(
                                                "contentId",
                                                aggregate,
                                                "subscriptionId",
                                                subscription.get("id")));
                        if (patient != null)
                            queueNotification(
                                    t,
                                    task.id(),
                                    patient,
                                    type,
                                    type.startsWith("Appointment") ? aggregate : null);
                        db.exec(
                                "insert into app.consumed_events(tenant_id,consumer_name,event_id)"
                                        + " values(?,'core',?) on conflict do nothing",
                                t.id(),
                                task.id());
                        return null;
                    });
            finish(task);
        } catch (Exception e) {
            retry(task, e);
        }
    }

    public static void requireEventVersion(int version) {
        Problem.require(version == 1, 422, "UNSUPPORTED_EVENT_VERSION");
    }

    private void invoiceCompleted(Tenant t, UUID appointment) {
        var ap =
                db.one(
                        "select * from app.appointments where tenant_id=? and id=?",
                        t.id(),
                        appointment);
        long amount = ((Number) ap.get("price_cents")).longValue();
        if (amount == 0
                || db.exists(
                        "select 1 from app.invoice_items where tenant_id=? and appointment_id=? and"
                                + " description_code='SESSION_PRIMARY'",
                        t.id(),
                        appointment)) return;
        var payer =
                db.optional(
                        "select id from app.invoice_payers where tenant_id=? and patient_id=? order"
                                + " by created_at limit 1",
                        t.id(),
                        ap.get("patient_id"));
        Problem.require(payer.isPresent(), 422, "PAYER_CONFIGURATION_REQUIRED");
        UUID invoice = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.invoices(tenant_id,id,payer_id,amount_cents,due_on,status,issued_at)"
                    + " values(?,?,?,?,?,'OPEN',now())",
                t.id(),
                invoice,
                payer.get().get("id"),
                amount,
                LocalDate.now(ZoneId.of(t.timezone())));
        db.exec(
                "insert into"
                    + " app.invoice_items(tenant_id,invoice_id,appointment_id,description_code,amount_cents)"
                    + " values(?,?,?,'SESSION_PRIMARY',?)",
                t.id(),
                invoice,
                appointment,
                amount);
        events.emit(actor(t), "InvoiceIssued", "invoice", invoice, 1);
    }

    private void queueNotification(
            Tenant t, UUID event, UUID patient, String type, UUID appointment) {
        var p =
                db.one(
                        "select portal_user_id,communication_preference from app.patients where"
                                + " tenant_id=? and id=?",
                        t.id(),
                        patient);
        UUID template = UUID.randomUUID();
        if (str(p, "communication_preference").equals("NONE")) return;
        var preference =
                p.get("portal_user_id") == null
                        ? Optional.<Map<String, Object>>empty()
                        : db.optional(
                                "select * from app.user_notification_preferences where tenant_id=?"
                                        + " and user_id=?",
                                t.id(),
                                p.get("portal_user_id"));
        String channel =
                str(p, "communication_preference").equals("WHATSAPP")
                                && preference
                                        .map(
                                                r ->
                                                        Boolean.TRUE.equals(
                                                                r.get("appointment_whatsapp")))
                                        .orElse(false)
                        ? "WHATSAPP"
                        : "EMAIL";
        if (channel.equals("EMAIL")
                && preference
                        .map(r -> Boolean.FALSE.equals(r.get("appointment_email")))
                        .orElse(false)) return;
        db.exec(
                "insert into"
                    + " app.notification_templates(tenant_id,id,template_code,version,channel,body_cipher)"
                    + " values(?,?,?,1,?,?) on conflict(tenant_id,template_code,version,channel) do"
                    + " nothing",
                t.id(),
                template,
                type,
                channel,
                crypto.encrypt(
                        t.id(),
                        "notification_templates",
                        template,
                        1,
                        "Há uma atualização no seu consultório. Acesse o portal privado para"
                                + " consultar os detalhes."));
        template =
                id(
                        db.one(
                                "select id from app.notification_templates where tenant_id=? and"
                                        + " template_code=? and version=1 and channel=?",
                                t.id(),
                                type,
                                channel),
                        "id");
        UUID token = null;
        if (appointment != null
                && Set.of("AppointmentScheduled", "AppointmentReminder").contains(type)) {
            var ap =
                    db.one(
                            "select status,start_at from app.appointments where tenant_id=? and"
                                    + " id=?",
                            t.id(),
                            appointment);
            Instant expiry = SchedulingModule.instant(ap, "start_at").minusSeconds(7200);
            if (expiry.isAfter(Instant.now()) && str(ap, "status").equals("SCHEDULED")) {
                expiry =
                        expiry.isBefore(Instant.now().plusSeconds(259200))
                                ? expiry
                                : Instant.now().plusSeconds(259200);
                token = UUID.randomUUID();
                var nonce = new byte[32];
                new java.security.SecureRandom().nextBytes(nonce);
                String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
                db.exec(
                        "insert into"
                            + " app.action_tokens(tenant_id,id,token_hash,purpose,patient_id,appointment_id,expires_at,delivery_cipher)"
                            + " values(?,?,?,'APPOINTMENT_CONFIRM',?,?,?,?)",
                        t.id(),
                        token,
                        hash(raw),
                        patient,
                        appointment,
                        expiry.atOffset(ZoneOffset.UTC),
                        crypto.encrypt(
                                t.id(),
                                "action_tokens",
                                token,
                                1,
                                object("token", raw).toString()));
            }
        }
        db.exec(
                "insert into"
                    + " app.notifications(tenant_id,event_id,recipient_user_id,patient_id,template_id,channel,request_reference,action_token_id)"
                    + " values(?,?,?,?,?,?,?,?) on conflict do nothing",
                t.id(),
                event,
                p.get("portal_user_id"),
                patient,
                template,
                channel,
                UUID.randomUUID(),
                token);
    }

    private void processJob(Tenant t) {
        Task task = claim(t, "jobs");
        if (task == null) return;
        try {
            String type = task.payload().path("type").asText();
            JsonNode payload = task.payload().path("row");
            switch (type) {
                case "VIDEO_PROVISION" ->
                        in(
                                t,
                                () -> {
                                    video.provision(actor(t), uuid(payload, "appointmentId"));
                                    return null;
                                });
                case "DAILY_REPORT" ->
                        in(
                                t,
                                () -> {
                                    JsonNode report =
                                            reporting.generate(
                                                    actor(t),
                                                    LocalDate.parse(
                                                            payload.path("localDate").asText()),
                                                    t.timezone());
                                    for (var m :
                                            db.rows(
                                                    "select id from app.organization_memberships"
                                                        + " where tenant_id=? and status='ACTIVE'"
                                                        + " and not service_account and"
                                                        + " email_cipher is not null and roles &&"
                                                        + " array['OWNER','ADMIN','SECRETARY','BILLING']::app.member_role[]",
                                                    t.id()))
                                        job(
                                                t,
                                                "DAILY_REPORT_EMAIL",
                                                report.path("id").asText() + ":" + m.get("id"),
                                                object(
                                                        "membershipId",
                                                        m.get("id"),
                                                        "reportId",
                                                        report.path("id").asText()));
                                    return null;
                                });
                case "DAILY_REPORT_EMAIL" -> sendReport(t, task.id(), payload);
                case "NEWSLETTER_CONFIRM" ->
                        sendNewsletterConfirmation(t, uuid(payload, "tokenId"));
                case "NEWSLETTER_SEND" -> sendNewsletter(t, task.id(), payload);
                case "APPOINTMENT_REMINDER" ->
                        in(
                                t,
                                () -> {
                                    var ap =
                                            db.optional(
                                                    "select * from app.appointments where"
                                                            + " tenant_id=? and id=? and status in"
                                                            + " ('SCHEDULED','CONFIRMED')",
                                                    t.id(),
                                                    uuid(payload, "appointmentId"));
                                    ap.ifPresent(
                                            row ->
                                                    queueNotification(
                                                            t,
                                                            task.id(),
                                                            id(row, "patient_id"),
                                                            "AppointmentReminder",
                                                            id(row, "id")));
                                    return null;
                                });
                case "RECURRENCE" ->
                        in(
                                t,
                                () -> {
                                    scheduling.expandSeries(actor(t), uuid(payload, "seriesId"));
                                    return null;
                                });
                case "PAYMENT_CREATE" -> createPayment(t, uuid(payload, "paymentId"));
                case "PLATFORM_SUBSCRIPTION" ->
                        subscriptions.process(actor(t), uuid(payload, "subscriptionId"));
                case "PAYMENT_RECONCILE" -> reconcile(t, uuid(payload, "paymentId"), task.id());
                case "INVITE" -> sendInvite(t, uuid(payload, "tokenId"));
                default -> throw new Problem(422, "UNKNOWN_JOB_TYPE");
            }
            finish(task);
        } catch (Exception e) {
            retry(task, e);
        }
    }

    private void createPayment(Tenant t, UUID payment) {
        JsonNode request =
                in(
                        t,
                        () -> {
                            var p =
                                    db.one(
                                            "select * from app.payments where tenant_id=? and id=?",
                                            t.id(),
                                            payment);
                            var i =
                                    db.one(
                                            "select * from app.invoices where tenant_id=? and id=?",
                                            t.id(),
                                            p.get("invoice_id"));
                            var payer =
                                    db.one(
                                            "select * from app.invoice_payers where tenant_id=? and"
                                                    + " id=?",
                                            t.id(),
                                            i.get("payer_id"));
                            UUID payerId = id(payer, "id");
                            String state =
                                    str(
                                            db.one(
                                                    "select status from app.provider_requests where"
                                                            + " tenant_id=? and aggregate_id=? and"
                                                            + " action='CREATE_PAYMENT'",
                                                    t.id(),
                                                    payment),
                                            "status");
                            db.exec(
                                    "update app.provider_requests set"
                                        + " status='IN_FLIGHT',attempts=attempts+1 where"
                                        + " tenant_id=? and aggregate_id=? and provider='ASAAS' and"
                                        + " action='CREATE_PAYMENT'",
                                    t.id(),
                                    payment);
                            return object(
                                    "allowCreate",
                                    state.equals("PENDING"),
                                    "payerId",
                                    payerId,
                                    "payerName",
                                    crypto.decrypt(
                                            t.id(),
                                            "invoice_payers.name",
                                            payerId,
                                            1,
                                            (byte[]) payer.get("name_cipher")),
                                    "payerTaxId",
                                    crypto.decrypt(
                                            t.id(),
                                            "invoice_payers.tax",
                                            payerId,
                                            1,
                                            (byte[]) payer.get("tax_id_cipher")),
                                    "customer",
                                    str(payer, "provider_customer_ref"),
                                    "amountCents",
                                    p.get("amount_cents"),
                                    "method",
                                    p.get("method"),
                                    "dueOn",
                                    i.get("due_on").toString());
                        });
        // Provider calls execute after the claim transaction commits, without holding invoice
        // locks.
        JsonNode canonical;
        try {
            canonical = withTenantRead(t, () -> payments.findOrCreate(t.id(), payment, request));
        } catch (Exception e) {
            in(
                    t,
                    () -> {
                        db.exec(
                                "update app.provider_requests set status='UNKNOWN' where"
                                    + " tenant_id=? and aggregate_id=? and action='CREATE_PAYMENT'",
                                t.id(),
                                payment);
                        return null;
                    });
            throw e;
        }
        in(
                t,
                () -> {
                    String url = canonical.path("invoiceUrl").asText();
                    Problem.require(
                            url.startsWith("https://")
                                    && (java.net.URI.create(url).getHost().endsWith(".asaas.com")
                                            || java.net
                                                    .URI
                                                    .create(url)
                                                    .getHost()
                                                    .endsWith(".asaas.com.br")),
                            422,
                            "INVALID_CHECKOUT_URL");
                    db.exec(
                            "update app.payments set provider_payment_ref=?,checkout_url=? where"
                                    + " tenant_id=? and id=?",
                            canonical.path("id").asText(),
                            url,
                            t.id(),
                            payment);
                    db.exec(
                            "update app.provider_requests set"
                                + " status='SUCCEEDED',external_reference=? where tenant_id=? and"
                                + " aggregate_id=?",
                            canonical.path("id").asText(),
                            t.id(),
                            payment);
                    billing.applyCanonical(actor(t), payment, UUID.randomUUID(), canonical);
                    return null;
                });
    }

    private <T> T withTenantRead(Tenant t, java.util.function.Supplier<T> external) {
        // Secret lookup is an independent short read transaction in a bound thread; network remains
        // outside invoice/job locks.
        return in(t, external);
    }

    private void reconcile(Tenant t, UUID payment, UUID event) {
        String ref =
                in(
                        t,
                        () ->
                                str(
                                        db.one(
                                                "select provider_payment_ref from app.payments"
                                                        + " where tenant_id=? and id=?",
                                                t.id(),
                                                payment),
                                        "provider_payment_ref"));
        JsonNode canonical = withTenantRead(t, () -> payments.canonical(t.id(), ref));
        in(
                t,
                () -> {
                    billing.applyCanonical(actor(t), payment, event, canonical);
                    return null;
                });
    }

    private void processWebhook(Tenant t) {
        Task task =
                in(
                        t,
                        () -> {
                            var row =
                                    db.optional(
                                            "select * from app.inbox_events where tenant_id=? and"
                                                + " (status='PENDING' or status='PROCESSING' and"
                                                + " locked_until<now()) and next_attempt_at<=now()"
                                                + " order by received_at for update skip locked"
                                                + " limit 1",
                                            t.id());
                            if (row.isEmpty()) return null;
                            var r = row.get();
                            db.exec(
                                    "update app.inbox_events set"
                                        + " status='PROCESSING',attempts=attempts+1,locked_until=now()+interval"
                                        + " '2 minutes' where tenant_id=? and id=?",
                                    t.id(),
                                    r.get("id"));
                            return new Task(
                                    t,
                                    id(r, "id"),
                                    "inbox_events",
                                    parse(
                                            crypto.decrypt(
                                                    t.id(),
                                                    "inbox_events",
                                                    id(r, "id"),
                                                    1,
                                                    (byte[]) r.get("payload_cipher"))),
                                    ((Number) r.get("attempts")).intValue() + 1);
                        });
        if (task == null) return;
        try {
            JsonNode incoming = task.payload();
            String ref = incoming.path("payment").path("id").asText();
            if (ref.isBlank()) {
                in(
                        t,
                        () -> {
                            db.exec(
                                    "update app.inbox_events set status='IGNORED' where tenant_id=?"
                                            + " and id=?",
                                    t.id(),
                                    task.id());
                            return null;
                        });
                return;
            }
            UUID payment =
                    in(
                            t,
                            () ->
                                    id(
                                            db.one(
                                                    "select id from app.payments where tenant_id=?"
                                                            + " and provider='ASAAS' and"
                                                            + " provider_payment_ref=?",
                                                    t.id(),
                                                    ref),
                                            "id"));
            JsonNode canonical = withTenantRead(t, () -> payments.canonical(t.id(), ref));
            in(
                    t,
                    () -> {
                        billing.applyCanonical(actor(t), payment, task.id(), canonical);
                        return null;
                    });
            finish(task);
        } catch (Exception e) {
            retry(task, e);
        }
    }

    private void sendInvite(Tenant t, UUID token) {
        JsonNode data =
                in(
                        t,
                        () -> {
                            var r =
                                    db.one(
                                            "select * from app.action_tokens where tenant_id=? and"
                                                    + " id=? and consumed_at is null and"
                                                    + " expires_at>now()",
                                            t.id(),
                                            token);
                            return parse(
                                    crypto.decrypt(
                                            t.id(),
                                            "action_tokens",
                                            token,
                                            1,
                                            (byte[]) r.get("delivery_cipher")));
                        });
        withTenantRead(
                t,
                () ->
                        comms.email(
                                t.id(),
                                token,
                                data.path("email").asText(),
                                "Seu convite para o Conhecer-se",
                                "Você recebeu um convite para acessar seu espaço privado.\n"
                                        + publicUrl
                                        + "/convite#token="
                                        + data.path("token").asText()
                                        + "\nO convite é pessoal e expira em 72 horas."));
    }

    private void sendReport(Tenant t, UUID id, JsonNode payload) {
        String email =
                in(
                        t,
                        () -> {
                            UUID member = uuid(payload, "membershipId");
                            var m =
                                    db.one(
                                            "select email_cipher from app.organization_memberships"
                                                + " where tenant_id=? and id=? and status='ACTIVE'",
                                            t.id(),
                                            member);
                            return crypto.decrypt(
                                    t.id(),
                                    "organization_memberships.email",
                                    member,
                                    1,
                                    (byte[]) m.get("email_cipher"));
                        });
        withTenantRead(
                t,
                () ->
                        comms.email(
                                t.id(),
                                id,
                                email,
                                "Seu relatório diário está disponível",
                                "O relatório de agenda de hoje e alterações do dia anterior estão"
                                        + " disponíveis no painel privado.\n"
                                        + publicUrl
                                        + "/app/orgs/"
                                        + t.id()
                                        + "/dashboard"));
    }

    private void sendNewsletterConfirmation(Tenant t, UUID token) {
        JsonNode data =
                in(
                        t,
                        () -> {
                            var r =
                                    db.one(
                                            "select delivery_cipher from app.action_tokens where"
                                                    + " tenant_id=? and id=? and"
                                                    + " purpose='NEWSLETTER_CONFIRM' and"
                                                    + " expires_at>now() and consumed_at is null",
                                            t.id(),
                                            token);
                            return parse(
                                    crypto.decrypt(
                                            t.id(),
                                            "action_tokens",
                                            token,
                                            1,
                                            (byte[]) r.get("delivery_cipher")));
                        });
        withTenantRead(
                t,
                () ->
                        comms.email(
                                t.id(),
                                token,
                                data.path("email").asText(),
                                "Confirme sua inscrição",
                                "Você solicitou receber conteúdos do consultório. Confirme pelo"
                                        + " link pessoal abaixo; se não solicitou, ignore esta"
                                        + " mensagem.\n"
                                        + publicUrl
                                        + "/inscricao#token="
                                        + data.path("token").asText()));
    }

    private void sendNewsletter(Tenant t, UUID id, JsonNode payload) {
        JsonNode delivery =
                in(
                        t,
                        () -> {
                            UUID subscription = uuid(payload, "subscriptionId");
                            var opt =
                                    db.optional(
                                            "select * from app.newsletter_subscriptions where"
                                                    + " tenant_id=? and id=? and unsubscribed_at is"
                                                    + " null and verified_at is not null",
                                            t.id(),
                                            subscription);
                            if (opt.isEmpty()) return null;
                            var s = opt.get();
                            var stored =
                                    db.one(
                                            "select delivery_cipher from app.jobs where tenant_id=?"
                                                    + " and id=?",
                                            t.id(),
                                            id);
                            if (stored.get("delivery_cipher") != null)
                                return parse(
                                        crypto.decrypt(
                                                t.id(),
                                                "jobs.delivery",
                                                id,
                                                1,
                                                (byte[]) stored.get("delivery_cipher")));
                            var c =
                                    db.one(
                                            "select title,body_sanitized_html from"
                                                + " app.content_entries where tenant_id=? and id=?"
                                                + " and status='PUBLISHED' and"
                                                + " entry_type='NEWSLETTER'",
                                            t.id(),
                                            uuid(payload, "contentId"));
                            var random = new byte[32];
                            new java.security.SecureRandom().nextBytes(random);
                            String token =
                                    Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                            db.exec(
                                    "insert into"
                                        + " app.action_tokens(tenant_id,token_hash,purpose,email_lookup_hmac,subscription_id,consent_generation,expires_at)"
                                        + " values(?,?,'UNSUBSCRIBE',?,?,?,now()+interval '10"
                                        + " years')",
                                    t.id(),
                                    hash(token),
                                    s.get("email_lookup_hmac"),
                                    subscription,
                                    s.get("consent_generation"));
                            JsonNode data =
                                    object(
                                            "email",
                                            crypto.decrypt(
                                                    t.id(),
                                                    "newsletter_subscriptions",
                                                    subscription,
                                                    1,
                                                    (byte[]) s.get("email_cipher")),
                                            "title",
                                            c.get("title"),
                                            "text",
                                            org.jsoup.Jsoup.parse(str(c, "body_sanitized_html"))
                                                            .text()
                                                    + "\nCancelar inscrição: "
                                                    + publicUrl
                                                    + "/descadastro#token="
                                                    + token);
                            db.exec(
                                    "update app.jobs set delivery_cipher=? where tenant_id=? and"
                                            + " id=?",
                                    crypto.encrypt(t.id(), "jobs.delivery", id, 1, data.toString()),
                                    t.id(),
                                    id);
                            return data;
                        });
        if (delivery == null) return;
        withTenantRead(
                t,
                () ->
                        comms.email(
                                t.id(),
                                id,
                                delivery.path("email").asText(),
                                delivery.path("title").asText(),
                                delivery.path("text").asText()));
    }

    private void sendNotification(Tenant t) {
        var row =
                in(
                        t,
                        () -> {
                            var n =
                                    db.optional(
                                            "select n.*,p.email_cipher,p.phone_cipher,p.version as"
                                                + " patient_version,p.communication_preference,nt.template_code"
                                                + " from app.notifications n join app.patients p on"
                                                + " p.tenant_id=n.tenant_id and p.id=n.patient_id"
                                                + " join app.notification_templates nt on"
                                                + " nt.tenant_id=n.tenant_id and"
                                                + " nt.id=n.template_id where n.tenant_id=? and"
                                                + " n.status='QUEUED' and n.due_at<=now() and"
                                                + " (n.locked_until is null or"
                                                + " n.locked_until<now()) order by n.due_at for"
                                                + " update of n skip locked limit 1",
                                            t.id());
                            if (n.isEmpty()) return null;
                            var r = n.get();
                            db.exec(
                                    "update app.notifications set locked_until=now()+interval '2"
                                            + " minutes',attempts=attempts+1 where tenant_id=? and"
                                            + " id=?",
                                    t.id(),
                                    r.get("id"));
                            return r;
                        });
        if (row == null) return;
        try {
            String email =
                    crypto.decrypt(
                            t.id(),
                            "patients.email",
                            id(row, "patient_id"),
                            ((Number) row.get("patient_version")).intValue(),
                            (byte[]) row.get("email_cipher"));
            if ((str(row, "channel").equals("EMAIL") && email.isBlank())
                    || str(row, "communication_preference").equals("NONE")) {
                in(
                        t,
                        () -> {
                            db.exec(
                                    "update app.notifications set"
                                        + " status='SUPPRESSED',locked_until=null where tenant_id=?"
                                        + " and id=?",
                                    t.id(),
                                    row.get("id"));
                            return null;
                        });
                return;
            }
            String ref;
            if (str(row, "channel").equals("WHATSAPP")) {
                String phone =
                        crypto.decrypt(
                                t.id(),
                                "patients.phone",
                                id(row, "patient_id"),
                                ((Number) row.get("patient_version")).intValue(),
                                (byte[]) row.get("phone_cipher"));
                ref =
                        withTenantRead(
                                t,
                                () ->
                                        comms.whatsapp(
                                                t.id(),
                                                id(row, "request_reference"),
                                                phone,
                                                str(row, "template_code")));
            } else {
                String link =
                        row.get("action_token_id") == null
                                ? publicUrl + "/portal"
                                : in(
                                        t,
                                        () -> {
                                            UUID token = id(row, "action_token_id");
                                            var r =
                                                    db.one(
                                                            "select delivery_cipher from"
                                                                    + " app.action_tokens where"
                                                                    + " tenant_id=? and id=?",
                                                            t.id(),
                                                            token);
                                            return publicUrl
                                                    + "/confirmar#token="
                                                    + parse(
                                                                    crypto.decrypt(
                                                                            t.id(),
                                                                            "action_tokens",
                                                                            token,
                                                                            1,
                                                                            (byte[])
                                                                                    r.get(
                                                                                            "delivery_cipher")))
                                                            .path("token")
                                                            .asText();
                                        });
                ref =
                        withTenantRead(
                                t,
                                () ->
                                        comms.email(
                                                t.id(),
                                                id(row, "request_reference"),
                                                email,
                                                "Atualização no seu consultório",
                                                "Você tem uma atualização no seu consultório."
                                                        + " Acesse o link pessoal: "
                                                        + link));
            }
            in(
                    t,
                    () -> {
                        db.exec(
                                "update app.notifications set status=coalesce((select"
                                    + " delivery_state::app.notification_status from"
                                    + " app.communication_sends where tenant_id=? and"
                                    + " request_id=?),'SENT'::app.notification_status),sent_at=now(),provider_message_ref=?,locked_until=null"
                                    + " where tenant_id=? and id=?",
                                t.id(),
                                row.get("request_reference"),
                                ref,
                                t.id(),
                                row.get("id"));
                        return null;
                    });
        } catch (Exception e) {
            in(
                    t,
                    () -> {
                        int attempts = ((Number) row.get("attempts")).intValue() + 1;
                        boolean uncertain =
                                e instanceof Problem p
                                        && p.code.equals("WHATSAPP_DELIVERY_UNCERTAIN");
                        db.exec(
                                "update app.notifications set"
                                    + " status=?::app.notification_status,failure_code=?,locked_until=null,due_at=now()+make_interval(secs=>?)"
                                    + " where tenant_id=? and id=?",
                                e instanceof Problem suppressed
                                                && suppressed.code.equals("RECIPIENT_SUPPRESSED")
                                        ? "SUPPRESSED"
                                        : attempts >= 4
                                                        || uncertain
                                                        || e instanceof Problem p
                                                                && p.status >= 400
                                                                && p.status < 500
                                                ? "FAILED"
                                                : "QUEUED",
                                uncertain ? "DELIVERY_UNCERTAIN" : "PROVIDER_UNAVAILABLE",
                                (attempts == 1 ? 60 : attempts == 2 ? 300 : 1800)
                                        + java.util.concurrent.ThreadLocalRandom.current()
                                                .nextInt(30),
                                t.id(),
                                row.get("id"));
                        return null;
                    });
        }
    }
}
