package com.psicocare.integrations;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class IntegrationsModule implements ModuleHandler, PaymentProvider {
    private final Store db;
    private final Crypto crypto;
    private final Events events;
    private final String environment;

    public IntegrationsModule(
            Store db,
            Crypto crypto,
            Events events,
            @Value("${app.environment}") String environment) {
        this.db = db;
        this.crypto = crypto;
        this.events = events;
        this.environment = environment;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Integrations") || c.tag().equals("Webhooks");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        String path = c.path();
        if (c.tag().equals("Webhooks")) {
            UUID connection = c.id("connectionId");
            UUID t =
                    id(
                            db.one(
                                    "select tenant_id from app.integration_routes where id=? and"
                                            + " provider='ASAAS'",
                                    connection),
                            "tenant_id");
            context(t);
            var config =
                    db.one(
                            "select * from app.integration_connections where tenant_id=? and"
                                + " webhook_identifier=? and provider='ASAAS' and status='ACTIVE'",
                            t,
                            connection);
            JsonNode secret = crypto.secret(str(config, "secrets_path"));
            // Authentication is checked before persistence by the dedicated webhook filter.
            Problem.require(
                    WebhookAuthentication.isAuthenticated(connection),
                    401,
                    "INVALID_WEBHOOK_SECRET");
            String providerEvent = b.path("id").asText();
            Problem.require(
                    !providerEvent.isBlank()
                            && providerEvent.length() <= 200
                            && b.path("event").isTextual(),
                    422,
                    "INVALID_PROVIDER_EVENT");
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.inbox_events(tenant_id,id,provider,provider_event_id,payload_cipher)"
                        + " values(?,?,'ASAAS',?,?) on"
                        + " conflict(tenant_id,provider,provider_event_id) do nothing",
                    t,
                    id,
                    providerEvent,
                    crypto.encrypt(t, "inbox_events", id, 1, b.toString()));
            return object("accepted", true);
        }
        a.require("OWNER", "ADMIN");
        if (path.endsWith("/release-approvals")) {
            if (c.isGet())
                return c.page(
                        db
                                .rows(
                                        "select * from app.release_approvals where tenant_id=?"
                                                + " order by approved_at desc",
                                        tenant)
                                .stream()
                                .map(
                                        r ->
                                                dto(
                                                        r,
                                                        "id",
                                                        "gate_code",
                                                        "evidence_reference",
                                                        "approved_by",
                                                        "approved_at"))
                                .toList());
            a.recentMfa();
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.release_approvals(tenant_id,id,gate_code,evidence_reference,approved_by)"
                        + " values(?,?,?,?,?)",
                    tenant,
                    id,
                    text(b, "gateCode"),
                    text(b, "evidenceReference"),
                    a.user());
            events.audit(a, "ReleaseGateApproved", "releaseApproval", id);
            return object("id", id, "version", 1);
        }
        if (c.isGet())
            return c.page(
                    db
                            .rows(
                                    "select * from app.integration_connections where tenant_id=?"
                                            + " order by provider,label",
                                    tenant)
                            .stream()
                            .map(
                                    r ->
                                            dto(
                                                    r,
                                                    "id",
                                                    "provider",
                                                    "label",
                                                    "external_account_ref",
                                                    "status",
                                                    "webhook_identifier",
                                                    "capabilities"))
                            .toList());
        if (path.endsWith("/activate")) {
            a.recentMfa();
            UUID id = c.id("connectionId");
            var config =
                    db.one(
                            "select * from app.integration_connections where tenant_id=? and id=?",
                            tenant,
                            id);
            var credential = crypto.secret(str(config, "secrets_path"));
            if (Set.of("EMAIL", "WHATSAPP").contains(str(config, "provider"))) {
                Problem.require(
                        db.exists(
                                "select 1 from app.release_approvals where tenant_id=? and"
                                    + " gate_code='GATE-COMMUNICATION-01'",
                                tenant),
                        422,
                        "COMMUNICATION_APPROVAL_REQUIRED");
                if (str(config, "provider").equals("EMAIL"))
                    Problem.require(
                            credential.path("webhookSecret").asText().startsWith("whsec_"),
                            422,
                            "CALLBACK_SECRET_REQUIRED");
                else
                    Problem.require(
                            credential.path("appSecret").asText().length() >= 32
                                    && credential.path("verifyToken").asText().length() >= 32,
                            422,
                            "CALLBACK_SECRET_REQUIRED");
            }
            if (str(config, "provider").equals("ASAAS"))
                Problem.require(
                        db.exists(
                                "select 1 from app.release_approvals where tenant_id=? and"
                                        + " gate_code='GATE-PSP-01'",
                                tenant),
                        422,
                        "PROVIDER_APPROVAL_REQUIRED");
            db.exec(
                    "update app.integration_connections set status='ACTIVE' where tenant_id=? and"
                            + " id=?",
                    tenant,
                    id);
            events.audit(a, "IntegrationActivated", "integration", id);
            return object("id", id, "version", 1);
        }
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.integration_connections(tenant_id,id,provider,label,secrets_path,external_account_ref,status,capabilities)"
                    + " values(?,?,?,?,?,?,'PENDING',?::jsonb)",
                tenant,
                id,
                text(b, "provider"),
                text(b, "label"),
                text(b, "secretsPath"),
                text(b, "externalAccountRef"),
                b.path("capabilities").isMissingNode() ? "{}" : b.path("capabilities").toString());
        events.audit(a, "IntegrationRegistered", "integration", id);
        return object("id", id, "version", 1);
    }

    public JsonNode connectionSecret(UUID tenant, String provider) {
        return crypto.secret(
                str(
                        db.one(
                                "select * from app.integration_connections where tenant_id=? and"
                                    + " provider=? and status='ACTIVE' order by created_at limit 1",
                                tenant,
                                provider),
                        "secrets_path"));
    }

    public JsonNode findOrCreate(UUID tenant, UUID paymentId, JsonNode request) {
        JsonNode secret = connectionSecret(tenant, "ASAAS");
        RestClient client = client(secret);
        JsonNode found =
                read(
                        client.get()
                                .uri(
                                        uri ->
                                                uri.path("/payments")
                                                        .queryParam("externalReference", paymentId)
                                                        .queryParam("limit", 10)
                                                        .build()));
        if (found.path("totalCount").asInt() > 0) {
            Problem.require(
                    found.path("totalCount").asInt() == 1, 409, "PROVIDER_DUPLICATE_REFERENCE");
            return found.path("data").get(0);
        }
        Problem.require(request.path("allowCreate").asBoolean(), 503, "PAYMENT_STATE_UNKNOWN");
        String customer = request.path("customer").asText();
        if (customer.isBlank()) {
            String payerId = request.path("payerId").asText();
            JsonNode customers =
                    read(
                            client.get()
                                    .uri(
                                            uri ->
                                                    uri.path("/customers")
                                                            .queryParam(
                                                                    "externalReference", payerId)
                                                            .queryParam("limit", 10)
                                                            .build()));
            if (customers.path("totalCount").asInt() > 0) {
                Problem.require(
                        customers.path("totalCount").asInt() == 1,
                        409,
                        "PROVIDER_DUPLICATE_REFERENCE");
                customer = customers.path("data").get(0).path("id").asText();
            } else
                customer =
                        read(client.post()
                                        .uri("/customers")
                                        .body(
                                                object(
                                                        "name",
                                                        request.path("payerName").asText(),
                                                        "cpfCnpj",
                                                        request.path("payerTaxId").asText(),
                                                        "externalReference",
                                                        payerId,
                                                        "notificationDisabled",
                                                        true)))
                                .path("id")
                                .asText();
        }
        String method = request.path("method").asText();
        String billingType = method.equals("BOLETO") ? "BOLETO" : method;
        return read(
                client.post()
                        .uri("/payments")
                        .body(
                                object(
                                        "customer",
                                        customer,
                                        "billingType",
                                        billingType,
                                        "value",
                                        java.math.BigDecimal.valueOf(
                                                request.path("amountCents").asLong(), 2),
                                        "dueDate",
                                        request.path("dueOn").asText(),
                                        "externalReference",
                                        paymentId.toString())));
    }

    public JsonNode canonical(UUID tenant, String providerPaymentId) {
        Problem.require(
                providerPaymentId.matches("pay_[a-zA-Z0-9]+"), 422, "INVALID_PROVIDER_REFERENCE");
        return read(
                client(connectionSecret(tenant, "ASAAS"))
                        .get()
                        .uri("/payments/" + providerPaymentId));
    }

    private RestClient client(JsonNode secret) {
        boolean live = secret.path("live").asBoolean();
        Problem.require(!live || environment.equals("production"), 422, "LIVE_PAYMENTS_DISABLED");
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .baseUrl(live ? "https://api.asaas.com/v3" : "https://api-sandbox.asaas.com/v3")
                .requestFactory(factory)
                .defaultHeader("access_token", secret.path("accessToken").asText())
                .defaultHeader("User-Agent", "Conhecerse/2.0")
                .build();
    }

    private JsonNode read(RestClient.RequestHeadersSpec<?> request) {
        try {
            return parse(request.retrieve().body(String.class));
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(503, "PAYMENT_PROVIDER_UNAVAILABLE");
        }
    }

    private void context(UUID tenant) {
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
    }
}
