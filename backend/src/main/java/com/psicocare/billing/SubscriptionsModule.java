package com.psicocare.billing;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

/** SaaS billing uses a platform-owned vault reference, never a clinic PSP connection. */
@Service
public class SubscriptionsModule implements ModuleHandler {
    private final Store db;
    private final Crypto crypto;
    private final Events events;
    private final TransactionTemplate tx;
    private final String secretPath, environment;

    public SubscriptionsModule(
            Store db,
            Crypto crypto,
            Events events,
            TransactionTemplate tx,
            @Value("${PLATFORM_BILLING_SECRETS_PATH:}") String secretPath,
            @Value("${app.environment}") String environment) {
        this.db = db;
        this.crypto = crypto;
        this.events = events;
        this.tx = tx;
        this.secretPath = secretPath;
        this.environment = environment;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Subscriptions");
    }

    private JsonNode config() {
        Problem.require(!secretPath.isBlank(), 503, "PLATFORM_BILLING_NOT_CONFIGURED");
        return crypto.secret(secretPath);
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID t = a.tenant();
        a.require("OWNER", "ADMIN");
        if (c.tail().equals("plans"))
            return object("items", config().path("plans"), "nextCursor", null);
        if (c.isGet()) {
            var list =
                    db
                            .rows(
                                    "select * from app.platform_subscriptions where tenant_id=?"
                                        + " order by started_at desc",
                                    t)
                            .stream()
                            .map(
                                    r -> {
                                        var o =
                                                (tools.jackson.databind.node.ObjectNode)
                                                        dto(
                                                                r,
                                                                "id",
                                                                "plan_code",
                                                                "status",
                                                                "amount_cents",
                                                                "terms_version",
                                                                "version",
                                                                "cancel_requested_at",
                                                                "last_reconciled_at",
                                                                "request_state");
                                        o.set(
                                                "charges",
                                                Json.MAPPER.valueToTree(
                                                        db
                                                                .rows(
                                                                        "select"
                                                                            + " provider_payment_ref,due_on,amount_cents,status,checkout_url"
                                                                            + " from"
                                                                            + " app.platform_subscription_charges"
                                                                            + " where tenant_id=?"
                                                                            + " and subscription_id=?"
                                                                            + " order by due_on"
                                                                            + " desc",
                                                                        t,
                                                                        r.get("id"))
                                                                .stream()
                                                                .map(
                                                                        p ->
                                                                                dto(
                                                                                        p,
                                                                                        "provider_payment_ref",
                                                                                        "due_on",
                                                                                        "amount_cents",
                                                                                        "status",
                                                                                        "checkout_url"))
                                                                .toList()));
                                        return o;
                                    })
                            .toList();
            return page(list);
        }
        a.recentMfa();
        if (Set.of("cancel", "reconcile").contains(c.tail())) {
            var r =
                    db.one(
                            "select * from app.platform_subscriptions where tenant_id=? and"
                                + " status<>'CANCELLED' for update",
                            t);
            UUID id = id(r, "id");
            if (c.tail().equals("cancel"))
                db.exec(
                        "update app.platform_subscriptions set"
                            + " cancel_requested_at=coalesce(cancel_requested_at,now()),version=version+1"
                            + " where tenant_id=? and id=?",
                        t,
                        id);
            enqueue(t, id, c.idempotencyKey().toString());
            events.audit(
                    a,
                    c.tail().equals("cancel")
                            ? "PlatformCancellationRequested"
                            : "PlatformReconciliationRequested",
                    "platformSubscription",
                    id);
            return object("id", id, "version", version(r) + 1);
        }
        Problem.require(
                db.exists(
                        "select 1 from app.release_approvals where tenant_id=? and"
                            + " gate_code='GATE-BUSINESS-01'",
                        t),
                422,
                "BUSINESS_APPROVAL_REQUIRED");
        JsonNode plan = null;
        for (JsonNode p : config().path("plans"))
            if (text(p, "code").equals(text(c.body(), "planCode"))) plan = p;
        Problem.require(
                plan != null
                        && plan.path("approved").asBoolean()
                        && plan.path("amountCents").asLong() > 0
                        && plan.path("termsVersion")
                                .asText()
                                .equals(text(c.body(), "termsVersion")),
                422,
                "PLAN_NOT_APPROVED");
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,19))", Object.class, t.toString());
        Problem.require(
                !db.exists(
                        "select 1 from app.platform_subscriptions where tenant_id=? and"
                            + " status<>'CANCELLED'",
                        t),
                409,
                "SUBSCRIPTION_EXISTS");
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.platform_subscriptions(tenant_id,id,plan_code,status,started_at,amount_cents,terms_version,customer_cipher)"
                    + " values(?,?,?,'TRIAL',now(),?,?,?)",
                t,
                id,
                text(c.body(), "planCode"),
                plan.path("amountCents").asLong(),
                text(c.body(), "termsVersion"),
                crypto.encrypt(
                        t,
                        "platform_subscriptions.customer",
                        id,
                        1,
                        c.body().path("customer").toString()));
        enqueue(t, id, id.toString());
        events.audit(a, "PlatformSubscriptionRequested", "platformSubscription", id);
        return object("id", id, "version", 1);
    }

    private void enqueue(UUID t, UUID id, String key) {
        db.exec(
                "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                    + " values(?,'PLATFORM_SUBSCRIPTION',?,?::jsonb) on conflict do nothing",
                t,
                key,
                object("subscriptionId", id).toString());
    }

    private <T> T in(Actor a, java.util.function.Supplier<T> work) {
        return tx.execute(
                status -> {
                    db.jdbc.queryForObject(
                            "select set_config('app.tenant_id',?,true)",
                            String.class,
                            a.tenant().toString());
                    db.jdbc.queryForObject(
                            "select set_config('app.actor_id',?,true)",
                            String.class,
                            a.user().toString());
                    return work.get();
                });
    }

    public void process(Actor a, UUID id) {
        var snapshot =
                in(
                        a,
                        () ->
                                db.one(
                                        "select * from app.platform_subscriptions where tenant_id=?"
                                            + " and id=?",
                                        a.tenant(),
                                        id));
        if (str(snapshot, "status").equals("CANCELLED")) return;
        JsonNode config = config();
        Problem.require(
                config.path("accountPurpose").asText().equals("PLATFORM_SAAS"),
                503,
                "PLATFORM_ACCOUNT_REQUIRED");
        String base =
                config.path("environment").asText().equals("production")
                        ? "https://api.asaas.com/v3"
                        : "https://api-sandbox.asaas.com/v3";
        Problem.require(
                !environment.equals("production")
                        || config.path("environment").asText().equals("production"),
                503,
                "PROVIDER_ENVIRONMENT_MISMATCH");
        var client =
                OutboundHttp.client(base)
                        .mutate()
                        .defaultHeader("access_token", text(config, "apiKey"))
                        .build();
        String ref = str(snapshot, "payment_provider_subscription_ref");
        if (ref.isBlank()) {
            var found =
                    read(
                            client.get()
                                    .uri(
                                            u ->
                                                    u.path("/subscriptions")
                                                            .queryParam("externalReference", id)
                                                            .queryParam("includeDeleted", true)
                                                            .build()));
            Problem.require(
                    found.path("totalCount").asInt() <= 1, 409, "PROVIDER_DUPLICATE_REFERENCE");
            if (found.path("totalCount").asInt() == 1)
                ref = found.path("data").get(0).path("id").asText();
            else {
                Problem.require(
                        str(snapshot, "request_state").equals("PENDING"),
                        503,
                        "SUBSCRIPTION_STATE_UNKNOWN");
                JsonNode customer =
                        parse(
                                crypto.decrypt(
                                        a.tenant(),
                                        "platform_subscriptions.customer",
                                        id,
                                        1,
                                        (byte[]) snapshot.get("customer_cipher")));
                var customers =
                        read(
                                client.get()
                                        .uri(
                                                u ->
                                                        u.path("/customers")
                                                                .queryParam(
                                                                        "externalReference",
                                                                        "saas-" + a.tenant())
                                                                .build()));
                Problem.require(
                        customers.path("totalCount").asInt() <= 1,
                        409,
                        "PROVIDER_DUPLICATE_REFERENCE");
                // Persist uncertainty before either non-idempotent provider creation. Never retry
                // blindly.
                Problem.require(
                        in(
                                        a,
                                        () ->
                                                db.exec(
                                                        "update app.platform_subscriptions set"
                                                            + " request_state='UNKNOWN' where"
                                                            + " tenant_id=? and id=? and"
                                                            + " request_state='PENDING'",
                                                        a.tenant(),
                                                        id))
                                == 1,
                        409,
                        "SUBSCRIPTION_STATE_UNKNOWN");
                String customerRef =
                        customers.path("totalCount").asInt() == 1
                                ? customers.path("data").get(0).path("id").asText()
                                : read(client.post()
                                                .uri("/customers")
                                                .body(
                                                        object(
                                                                "name",
                                                                text(customer, "name"),
                                                                "email",
                                                                text(customer, "email"),
                                                                "cpfCnpj",
                                                                text(customer, "cpfCnpj"),
                                                                "externalReference",
                                                                "saas-" + a.tenant(),
                                                                "notificationDisabled",
                                                                true)))
                                        .path("id")
                                        .asText();
                ref =
                        read(client.post()
                                        .uri("/subscriptions")
                                        .body(
                                                object(
                                                        "customer",
                                                        customerRef,
                                                        "billingType",
                                                        "UNDEFINED",
                                                        "cycle",
                                                        "MONTHLY",
                                                        "value",
                                                        java.math.BigDecimal.valueOf(
                                                                ((Number)
                                                                                snapshot.get(
                                                                                        "amount_cents"))
                                                                        .longValue(),
                                                                2),
                                                        "nextDueDate",
                                                        LocalDate.now(
                                                                        ZoneId.of(
                                                                                "America/Sao_Paulo"))
                                                                .toString(),
                                                        "externalReference",
                                                        id.toString(),
                                                        "description",
                                                        "Assinatura da plataforma")))
                                .path("id")
                                .asText();
            }
            String resolved = ref;
            Problem.require(ref.matches("sub_[A-Za-z0-9]+"), 422, "INVALID_PROVIDER_REFERENCE");
            in(
                    a,
                    () ->
                            db.exec(
                                    "update app.platform_subscriptions set"
                                        + " payment_provider_subscription_ref=?,request_state='CONFIRMED'"
                                        + " where tenant_id=? and id=?",
                                    resolved,
                                    a.tenant(),
                                    id));
        }
        Problem.require(ref.matches("sub_[A-Za-z0-9]+"), 422, "INVALID_PROVIDER_REFERENCE");
        JsonNode canonical = read(client.get().uri("/subscriptions/" + ref));
        if (snapshot.get("cancel_requested_at") != null && !canonical.path("deleted").asBoolean()) {
            read(client.delete().uri("/subscriptions/" + ref));
            canonical = read(client.get().uri("/subscriptions/" + ref));
        }
        var charges = new ArrayList<JsonNode>();
        for (int offset = 0; offset < 10000; offset += 100) {
            var response =
                    read(
                            client.get()
                                    .uri(
                                            "/subscriptions/"
                                                    + ref
                                                    + "/payments?limit=100&offset="
                                                    + offset));
            response.path("data").forEach(charges::add);
            if (!response.path("hasMore").asBoolean()) break;
            Problem.require(offset < 9900, 503, "PROVIDER_PAGE_LIMIT");
        }
        String status =
                canonical.path("deleted").asBoolean()
                        ? "CANCELLED"
                        : !canonical.path("status").asText().equals("ACTIVE")
                                ? "SUSPENDED"
                                : charges.stream()
                                                .anyMatch(
                                                        p ->
                                                                p.path("status")
                                                                        .asText()
                                                                        .equals("OVERDUE"))
                                        ? "PAST_DUE"
                                        : charges.stream()
                                                        .anyMatch(
                                                                p ->
                                                                        Set.of(
                                                                                        "RECEIVED",
                                                                                        "CONFIRMED",
                                                                                        "RECEIVED_IN_CASH")
                                                                                .contains(
                                                                                        p.path(
                                                                                                        "status")
                                                                                                .asText()))
                                                ? "ACTIVE"
                                                : "TRIAL";
        in(
                a,
                () -> {
                    for (var charge : charges) {
                        String url = charge.path("invoiceUrl").asText();
                        if (!url.isBlank()) {
                            var uri = java.net.URI.create(url);
                            Problem.require(
                                    "https".equals(uri.getScheme())
                                            && (uri.getHost().endsWith(".asaas.com")
                                                    || uri.getHost().endsWith(".asaas.com.br")),
                                    422,
                                    "INVALID_CHECKOUT_URL");
                        }
                        db.exec(
                                "insert into"
                                    + " app.platform_subscription_charges(tenant_id,subscription_id,provider_payment_ref,due_on,amount_cents,status,checkout_url)"
                                    + " values(?,?,?,?,?,?,?) on"
                                    + " conflict(tenant_id,provider_payment_ref) do update set"
                                    + " status=excluded.status,checkout_url=excluded.checkout_url",
                                a.tenant(),
                                id,
                                text(charge, "id"),
                                LocalDate.parse(text(charge, "dueDate")),
                                charge.path("value")
                                        .decimalValue()
                                        .movePointRight(2)
                                        .longValueExact(),
                                text(charge, "status"),
                                url.isBlank() ? null : url);
                    }
                    db.exec(
                            "update app.platform_subscriptions set"
                                + " status=?,provider_state=?,last_reconciled_at=now(),version=version+1"
                                + " where tenant_id=? and id=?",
                            status,
                            status,
                            a.tenant(),
                            id);
                    events.audit(a, "PlatformSubscriptionReconciled", "platformSubscription", id);
                    return null;
                });
    }

    private JsonNode read(RestClient.RequestHeadersSpec<?> spec) {
        try {
            return parse(spec.retrieve().body(String.class));
        } catch (Exception e) {
            throw new Problem(503, "PLATFORM_PROVIDER_UNAVAILABLE");
        }
    }
}
