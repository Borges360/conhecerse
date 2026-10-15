package com.psicocare.billing;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class BillingModule implements ModuleHandler {
    private final Store db;
    private final Crypto crypto;
    private final Access access;
    private final Events events;

    public BillingModule(Store db, Crypto crypto, Access access, Events events) {
        this.db = db;
        this.crypto = crypto;
        this.access = access;
        this.events = events;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Billing") || c.path().endsWith("/portal/invoices");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        String path = c.path();
        if (path.contains("/prices")) {
            UUID pro = c.id("professionalId");
            if (c.isGet())
                return c.page(
                        db
                                .rows(
                                        "select * from app.price_rules where tenant_id=? and"
                                                + " professional_id=? order by effective_from desc",
                                        tenant,
                                        pro)
                                .stream()
                                .map(
                                        r ->
                                                dto(
                                                        r,
                                                        "id",
                                                        "modality",
                                                        "amount_cents",
                                                        "effective_from",
                                                        "effective_until",
                                                        "status"))
                                .toList());
            a.require("OWNER", "ADMIN");
            LocalDate from = LocalDate.parse(text(b, "effectiveFrom")),
                    to =
                            b.has("effectiveUntil")
                                    ? LocalDate.parse(text(b, "effectiveUntil"))
                                    : null;
            Problem.require(to == null || !to.isBefore(from), 422, "INVALID_PRICE_PERIOD");
            db.jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?,8))",
                    Object.class,
                    tenant + ":" + pro + ":" + text(b, "modality"));
            Problem.require(
                    !db.exists(
                            "select 1 from app.price_rules where tenant_id=? and professional_id=?"
                                    + " and modality=? and status='ACTIVE' and"
                                    + " daterange(effective_from,effective_until,'[]') &&"
                                    + " daterange(?,?,'[]')",
                            tenant,
                            pro,
                            text(b, "modality"),
                            from,
                            to),
                    409,
                    "PRICE_PERIOD_OVERLAP");
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.price_rules(tenant_id,id,professional_id,modality,amount_cents,effective_from,effective_until)"
                        + " values(?,?,?,?,?,?,?)",
                    tenant,
                    id,
                    pro,
                    text(b, "modality"),
                    b.path("amountCents").asLong(),
                    from,
                    to);
            events.audit(a, "PriceRuleCreated", "priceRule", id);
            return object("id", id, "version", 1);
        }
        if (path.endsWith("/payers")) {
            if (c.isGet()) {
                a.require("OWNER", "ADMIN", "BILLING");
                return c.page(
                        db
                                .rows(
                                        "select * from app.invoice_payers where tenant_id=? order"
                                                + " by created_at desc",
                                        tenant)
                                .stream()
                                .map(
                                        r ->
                                                object(
                                                        "id",
                                                        r.get("id"),
                                                        "patientId",
                                                        r.get("patient_id"),
                                                        "displayName",
                                                        crypto.decrypt(
                                                                tenant,
                                                                "invoice_payers.name",
                                                                id(r, "id"),
                                                                1,
                                                                (byte[]) r.get("name_cipher")),
                                                        "version",
                                                        1))
                                .toList());
            }
            UUID id = UUID.randomUUID(), patient = b.has("patientId") ? uuid(b, "patientId") : null;
            if (patient != null) access.patient(a, patient, false);
            db.exec(
                    "insert into"
                        + " app.invoice_payers(tenant_id,id,patient_id,name_cipher,tax_id_cipher,tax_id_lookup_hmac,person_type)"
                        + " values(?,?,?,?,?,?,?)",
                    tenant,
                    id,
                    patient,
                    crypto.encrypt(tenant, "invoice_payers.name", id, 1, text(b, "fullName")),
                    crypto.encrypt(tenant, "invoice_payers.tax", id, 1, text(b, "cpfCnpj")),
                    text(b, "cpfCnpj").isBlank() ? null : crypto.lookup(tenant, text(b, "cpfCnpj")),
                    text(b, "type"));
            events.audit(a, "PayerCreated", "payer", id);
            return object("id", id, "version", 1);
        }
        if (path.endsWith("/financial-reports")) {
            a.require("OWNER", "ADMIN", "BILLING");
            var filter = invoiceFilter(c);
            return page(
                    db
                            .rows(
                                    "select i.status,sum(i.amount_cents) as"
                                        + " invoiced_cents,sum(i.paid_cents) as"
                                        + " received_cents,count(*) as count from app.invoices i"
                                        + " where "
                                            + filter.sql()
                                            + " group by i.status order by i.status",
                                    filter.args().toArray())
                            .stream()
                            .map(r -> dto(r, "status", "invoiced_cents", "received_cents", "count"))
                            .toList());
        }
        if (c.vars().containsKey("paymentId")) {
            var p =
                    db.one(
                            "select * from app.payments where tenant_id=? and id=?",
                            tenant,
                            c.id("paymentId"));
            var i =
                    db.one(
                            "select * from app.invoices where tenant_id=? and id=?",
                            tenant,
                            p.get("invoice_id"));
            access.billing(a, i, false);
            return object(
                    "paymentId",
                    p.get("id"),
                    "status",
                    p.get("status"),
                    "checkoutUrl",
                    p.get("checkout_url"),
                    "failureCode",
                    p.get("failure_code"));
        }
        if (c.vars().containsKey("invoiceId")) {
            UUID iid = c.id("invoiceId");
            var i =
                    db.one(
                            "select * from app.invoices where tenant_id=? and id=?"
                                    + (c.isGet() ? "" : " for update"),
                            tenant,
                            iid);
            access.billing(a, i, !c.isGet() && !path.endsWith("/payments"));
            if (c.isGet()) return invoice(i);
            if (path.endsWith("/actions/issue")) {
                Problem.require(str(i, "status").equals("DRAFT"), 409, "INVALID_TRANSITION");
                db.exec(
                        "update app.invoices set status='OPEN',issued_at=now(),version=version+1"
                                + " where tenant_id=? and id=?",
                        tenant,
                        iid);
                events.emit(a, "InvoiceIssued", "invoice", iid, version(i) + 1);
            } else if (path.endsWith("/actions/cancel")) {
                Problem.require(
                        Set.of("DRAFT", "OPEN").contains(str(i, "status"))
                                && ((Number) i.get("paid_cents")).longValue() == 0,
                        409,
                        "INVALID_TRANSITION");
                Problem.require(
                        !db.exists(
                                "select 1 from app.payments where tenant_id=? and invoice_id=? and"
                                        + " status in ('PENDING','AUTHORIZED','CONFIRMED')",
                                tenant,
                                iid),
                        409,
                        "PENDING_PAYMENT_REQUIRES_RECONCILIATION");
                db.exec(
                        "update app.invoices set status='CANCELLED',version=version+1 where"
                                + " tenant_id=? and id=?",
                        tenant,
                        iid);
                events.audit(a, "InvoiceCancelled", "invoice", iid);
            } else if (path.endsWith("/manual-payments")) {
                a.require("OWNER", "ADMIN", "BILLING");
                Problem.require(
                        Set.of("OPEN", "OVERDUE", "PARTIALLY_PAID").contains(str(i, "status")),
                        409,
                        "INVALID_TRANSITION");
                long value = b.path("amountCents").asLong();
                long remaining =
                        ((Number) i.get("amount_cents")).longValue()
                                - ((Number) i.get("paid_cents")).longValue();
                Problem.require(value > 0 && value <= remaining, 422, "INVALID_AMOUNT");
                if (b.has("receivedOn"))
                    Problem.require(
                            !LocalDate.parse(text(b, "receivedOn")).isAfter(LocalDate.now()),
                            422,
                            "INVALID_RECEIVED_DATE");
                UUID pid = UUID.randomUUID(), source = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.payments(tenant_id,id,invoice_id,provider,status,amount_cents,received_cents,method,manual_reason,confirmed_at,settled_at,recorded_by)"
                            + " values(?,?,?,'MANUAL','SETTLED',?,?,'MANUAL',?,now(),now(),?)",
                        tenant,
                        pid,
                        iid,
                        value,
                        value,
                        text(b, "reason"),
                        a.user());
                db.exec(
                        "insert into"
                            + " app.ledger_entries(tenant_id,invoice_id,payment_id,source_event_id,entry_kind,amount_cents,reason)"
                            + " values(?,?,?,?,'RECEIVED',?,'AUDITED_MANUAL_PAYMENT')",
                        tenant,
                        iid,
                        pid,
                        source,
                        value);
                reconcileInvoice(a, iid);
                receiptForPayment(tenant, pid, iid);
                events.audit(a, "ManualPaymentRecorded", "payment", pid);
            } else if (path.endsWith("/payments")) {
                access.billing(a, i, false);
                Problem.require(
                        Set.of("OPEN", "OVERDUE", "PARTIALLY_PAID").contains(str(i, "status")),
                        409,
                        "INVALID_TRANSITION");
                Problem.require(
                        db.exists(
                                        "select 1 from app.integration_connections where"
                                                + " tenant_id=? and provider='ASAAS' and"
                                                + " status='ACTIVE'",
                                        tenant)
                                && db.exists(
                                        "select 1 from app.release_approvals where tenant_id=? and"
                                                + " gate_code='GATE-PSP-01'",
                                        tenant),
                        503,
                        "PAYMENT_PROVIDER_NOT_APPROVED");
                Problem.require(
                        !db.exists(
                                "select 1 from app.payments where tenant_id=? and invoice_id=? and"
                                        + " provider='ASAAS' and status in"
                                        + " ('PENDING','AUTHORIZED','CONFIRMED')",
                                tenant,
                                iid),
                        409,
                        "PAYMENT_ALREADY_PENDING");
                UUID pid = UUID.randomUUID();
                long value =
                        ((Number) i.get("amount_cents")).longValue()
                                - ((Number) i.get("paid_cents")).longValue();
                db.exec(
                        "insert into"
                            + " app.payments(tenant_id,id,invoice_id,provider,amount_cents,method)"
                            + " values(?,?,?,'ASAAS',?,?)",
                        tenant,
                        pid,
                        iid,
                        value,
                        text(b, "method"));
                db.exec(
                        "insert into"
                            + " app.provider_requests(tenant_id,provider,action,aggregate_id,idempotency_key,request_hash)"
                            + " values(?,'ASAAS','CREATE_PAYMENT',?,?,?)",
                        tenant,
                        pid,
                        pid,
                        hash(pid.toString()));
                db.exec(
                        "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                                + " values(?,'PAYMENT_CREATE',?,?::jsonb)",
                        tenant,
                        pid.toString(),
                        object("paymentId", pid).toString());
                events.audit(a, "PaymentRequested", "payment", pid);
                return object(
                        "paymentId",
                        pid,
                        "checkoutUrl",
                        System.getenv().getOrDefault("PUBLIC_URL", "http://localhost:3000")
                                + "/portal/pagamento/"
                                + pid
                                + "?org="
                                + tenant,
                        "status",
                        "PENDING");
            }
            return invoice(
                    db.one("select * from app.invoices where tenant_id=? and id=?", tenant, iid));
        }
        if (c.isGet()) {
            boolean portal = path.contains("/portal/");
            boolean staff = a.has("OWNER", "ADMIN", "BILLING") && !portal;
            var filter = invoiceFilter(c);
            if (!staff) {
                filter.args().add(a.user());
            }
            filter.args().add(c.limit() + 1);
            filter.args().add(c.offset());
            return c.sqlPage(
                    db
                            .rows(
                                    "select i.* from app.invoices i join app.invoice_payers ip on"
                                        + " ip.tenant_id=i.tenant_id and ip.id=i.payer_id left join"
                                        + " app.patients p on p.tenant_id=ip.tenant_id and"
                                        + " p.id=ip.patient_id where "
                                            + filter.sql()
                                            + (staff ? "" : " and p.portal_user_id=?")
                                            + " order by i.created_at desc,i.id limit ? offset ?",
                                    filter.args().toArray())
                            .stream()
                            .map(this::invoice)
                            .toList());
        }
        a.require("OWNER", "ADMIN", "BILLING");
        UUID payer = uuid(b, "payerId");
        db.one("select * from app.invoice_payers where tenant_id=? and id=?", tenant, payer);
        long total = 0;
        for (JsonNode item : b.path("items"))
            total = Math.addExact(total, item.path("amountCents").asLong());
        Problem.require(total <= 2000000 && total > 0, 422, "INVALID_AMOUNT");
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into app.invoices(tenant_id,id,payer_id,amount_cents,due_on)"
                        + " values(?,?,?,?,?)",
                tenant,
                id,
                payer,
                total,
                LocalDate.parse(text(b, "dueOn")));
        for (JsonNode item : b.path("items")) {
            UUID ap = item.has("appointmentId") ? uuid(item, "appointmentId") : null;
            if (ap != null)
                Problem.require(
                        db.exists(
                                "select 1 from app.appointments ap join app.invoice_payers ip on"
                                    + " ip.tenant_id=ap.tenant_id and ip.patient_id=ap.patient_id"
                                    + " where ap.tenant_id=? and ap.id=? and ip.id=? and ap.status"
                                    + " in ('COMPLETED','NO_SHOW','CANCELLED')",
                                tenant,
                                ap,
                                payer),
                        422,
                        "INVOICE_PATIENT_MISMATCH");
            db.exec(
                    "insert into"
                        + " app.invoice_items(tenant_id,invoice_id,appointment_id,description_code,amount_cents)"
                        + " values(?,?,?,?,?)",
                    tenant,
                    id,
                    ap,
                    text(item, "descriptionCode"),
                    item.path("amountCents").asLong());
        }
        events.audit(a, "InvoiceCreated", "invoice", id);
        return invoice(db.one("select * from app.invoices where tenant_id=? and id=?", tenant, id));
    }

    public void applyCanonical(Actor a, UUID paymentId, UUID sourceEvent, JsonNode canonical) {
        UUID tenant = a.tenant();
        var p =
                db.one(
                        "select * from app.payments where tenant_id=? and id=? for update",
                        tenant,
                        paymentId);
        UUID invoiceId = id(p, "invoice_id");
        var i =
                db.one(
                        "select * from app.invoices where tenant_id=? and id=? for update",
                        tenant,
                        invoiceId);
        String external = canonical.path("status").asText();
        String target =
                switch (external) {
                    case "RECEIVED", "RECEIVED_IN_CASH" -> "SETTLED";
                    case "CONFIRMED" -> "CONFIRMED";
                    case "REFUNDED" -> "REFUNDED";
                    case "CHARGEBACK_REQUESTED",
                            "CHARGEBACK_DISPUTE",
                            "AWAITING_CHARGEBACK_REVERSAL" ->
                            "CHARGEBACK";
                    case "DELETED" -> "FAILED";
                    default -> "PENDING";
                };
        long amount = ((Number) p.get("amount_cents")).longValue();
        if (canonical.has("value"))
            Problem.require(
                    canonical.path("value").decimalValue().movePointRight(2).longValueExact()
                            == amount,
                    422,
                    "PAYMENT_AMOUNT_MISMATCH");
        Problem.require(
                canonical.path("externalReference").asText().equals(paymentId.toString()),
                422,
                "PAYMENT_REFERENCE_MISMATCH");
        String old = str(p, "status");
        if (Set.of("SETTLED", "REFUNDED", "CHARGEBACK").contains(old)
                && Set.of("PENDING", "CONFIRMED").contains(target)) return;
        long received =
                target.equals("SETTLED")
                        ? amount
                        : target.equals("REFUNDED") || target.equals("CHARGEBACK")
                                ? 0
                                : ((Number) p.get("received_cents")).longValue();
        long previous = ((Number) p.get("received_cents")).longValue();
        long delta = received - previous;
        if (old.equals(target) && delta == 0) return;
        if (delta != 0) {
            String kind =
                    delta > 0
                            ? "RECEIVED"
                            : target.equals("CHARGEBACK") ? "CHARGEBACK" : "REFUNDED";
            db.exec(
                    "insert into"
                        + " app.ledger_entries(tenant_id,invoice_id,payment_id,source_event_id,entry_kind,amount_cents)"
                        + " values(?,?,?,?,?,?) on conflict(tenant_id,source_event_id,entry_kind)"
                        + " do nothing",
                    tenant,
                    invoiceId,
                    paymentId,
                    sourceEvent,
                    kind,
                    delta);
        }
        db.exec(
                "update app.payments set"
                    + " status=?::app.payment_status,received_cents=?,version=version+1,confirmed_at=case"
                    + " when ? in ('CONFIRMED','SETTLED') then coalesce(confirmed_at,now()) else"
                    + " confirmed_at end,settled_at=case when ?='SETTLED' then"
                    + " coalesce(settled_at,now()) else settled_at end where tenant_id=? and id=?",
                target,
                received,
                target,
                target,
                tenant,
                paymentId);
        reconcileInvoice(a, invoiceId);
        if (target.equals("CHARGEBACK"))
            db.exec(
                    "update app.invoices set status='DISPUTED' where tenant_id=? and id=?",
                    tenant,
                    invoiceId);
        else if (target.equals("REFUNDED"))
            db.exec(
                    "update app.invoices set status='REFUNDED' where tenant_id=? and id=? and"
                            + " paid_cents=0",
                    tenant,
                    invoiceId);
        if (received > 0) receiptForPayment(tenant, paymentId, invoiceId);
        if (target.equals("REFUNDED") || target.equals("CHARGEBACK"))
            db.exec(
                    "update app.receipt_references set status='REVIEW_REQUIRED' where tenant_id=?"
                            + " and payment_id=? and status<>'VOIDED_EXTERNAL'",
                    tenant,
                    paymentId);
    }

    private void reconcileInvoice(Actor a, UUID invoice) {
        var i =
                db.one(
                        "select * from app.invoices where tenant_id=? and id=? for update",
                        a.tenant(),
                        invoice);
        long total = ((Number) i.get("amount_cents")).longValue();
        long sum =
                db.jdbc.queryForObject(
                        "select coalesce(sum(amount_cents),0) from app.ledger_entries where"
                                + " tenant_id=? and invoice_id=?",
                        Long.class,
                        a.tenant(),
                        invoice);
        Problem.require(sum >= 0 && sum <= total, 409, "LEDGER_DIVERGENCE");
        String state = sum >= total ? "PAID" : sum > 0 ? "PARTIALLY_PAID" : "OPEN";
        db.exec(
                "update app.invoices set"
                        + " paid_cents=?,status=?::app.invoice_status,version=version+1 where"
                        + " tenant_id=? and id=?",
                sum,
                state,
                a.tenant(),
                invoice);
        if (!state.equals(str(i, "status")))
            events.emit(a, "PaymentStateUpdated", "invoice", invoice, version(i) + 1);
    }

    private void receiptForPayment(UUID tenant, UUID payment, UUID invoice) {
        db.exec(
                "insert into"
                    + " app.receipt_references(tenant_id,payment_id,patient_id,payer_id,amount_cents)"
                    + " select ?,?,ip.patient_id,i.payer_id,p.received_cents from app.invoices i"
                    + " join app.invoice_payers ip on ip.tenant_id=i.tenant_id and ip.id=i.payer_id"
                    + " join app.payments p on p.tenant_id=i.tenant_id and p.invoice_id=i.id and"
                    + " p.id=? where i.tenant_id=? and i.id=? and ip.patient_id is not null on"
                    + " conflict(tenant_id,payment_id) do nothing",
                tenant,
                payment,
                payment,
                tenant,
                invoice);
    }

    record InvoiceFilter(String sql, List<Object> args) {}

    private InvoiceFilter invoiceFilter(Command c) {
        var sql = new StringBuilder("i.tenant_id=?");
        var args = new ArrayList<Object>();
        args.add(c.actor().tenant());
        if (c.query().containsKey("from")) {
            sql.append(" and i.due_on>=?");
            args.add(LocalDate.parse(c.query().get("from")));
        }
        if (c.query().containsKey("to")) {
            sql.append(" and i.due_on<=?");
            args.add(LocalDate.parse(c.query().get("to")));
        }
        if (c.query().containsKey("status")) {
            sql.append(" and i.status::text=?");
            args.add(c.query().get("status"));
        }
        if (c.query().containsKey("professionalId")) {
            sql.append(
                    " and exists(select 1 from app.invoice_items it join app.appointments ap on"
                            + " ap.tenant_id=it.tenant_id and ap.id=it.appointment_id where"
                            + " it.tenant_id=i.tenant_id and it.invoice_id=i.id and"
                            + " ap.professional_id::text=?)");
            args.add(c.query().get("professionalId"));
        }
        if (c.query().containsKey("method")) {
            sql.append(
                    " and exists(select 1 from app.payments p where p.tenant_id=i.tenant_id and"
                            + " p.invoice_id=i.id and p.method=?)");
            args.add(c.query().get("method"));
        }
        return new InvoiceFilter(sql.toString(), args);
    }

    private JsonNode invoice(Map<String, Object> i) {
        return dto(
                i, "id", "payer_id", "amount_cents", "paid_cents", "due_on", "status", "version");
    }
}
