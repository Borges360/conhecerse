package com.psicocare.communications;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Service
public class CommunicationGateway {
    private final Store db;
    private final Crypto crypto;

    public CommunicationGateway(Store db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    private JsonNode secret(UUID tenant, String provider) {
        return crypto.secret(
                str(
                        db.one(
                                "select secrets_path from app.integration_connections where"
                                        + " tenant_id=? and provider=? and status='ACTIVE' order by"
                                        + " created_at limit 1",
                                tenant,
                                provider),
                        "secrets_path"));
    }

    public String email(UUID tenant, UUID requestId, String to, String subject, String plainText) {
        beforeSend(tenant, requestId, "EMAIL", to);
        var secret = secret(tenant, "EMAIL");
        Problem.require(
                secret.path("provider").asText().equals("RESEND"),
                503,
                "EMAIL_PROVIDER_NOT_CONFIGURED");
        try {
            String result =
                    client("https://api.resend.com")
                            .post()
                            .uri("/emails")
                            .header("Authorization", "Bearer " + secret.path("apiKey").asText())
                            .header("Idempotency-Key", requestId.toString())
                            .body(
                                    object(
                                            "from",
                                            secret.path("from").asText(),
                                            "to",
                                            List.of(to),
                                            "subject",
                                            subject,
                                            "text",
                                            plainText))
                            .retrieve()
                            .body(String.class);
            String ref = parse(result).path("id").asText();
            sent(tenant, requestId, ref);
            return ref;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429)
                throw new Problem(503, "EMAIL_PROVIDER_UNAVAILABLE");
            throw new Problem(422, "EMAIL_PROVIDER_REJECTED");
        } catch (Exception e) {
            throw new Problem(503, "EMAIL_PROVIDER_UNAVAILABLE");
        }
    }

    public String whatsapp(UUID tenant, UUID requestId, String to, String event) {
        beforeSend(tenant, requestId, "WHATSAPP", to);
        var secret = secret(tenant, "WHATSAPP");
        String template = secret.path("approvedTemplates").path(event).asText();
        Problem.require(
                !template.isBlank() && secret.path("phoneNumberId").asText().matches("[0-9]+"),
                422,
                "WHATSAPP_TEMPLATE_NOT_APPROVED");
        try {
            String result =
                    client("https://graph.facebook.com/v23.0")
                            .post()
                            .uri("/" + secret.path("phoneNumberId").asText() + "/messages")
                            .header(
                                    "Authorization",
                                    "Bearer " + secret.path("accessToken").asText())
                            .body(
                                    object(
                                            "messaging_product",
                                            "whatsapp",
                                            "to",
                                            to,
                                            "type",
                                            "template",
                                            "template",
                                            object(
                                                    "name",
                                                    template,
                                                    "language",
                                                    object("code", "pt_BR")),
                                            "biz_opaque_callback_data",
                                            requestId.toString()))
                            .retrieve()
                            .body(String.class);
            String ref = parse(result).path("messages").get(0).path("id").asText();
            sent(tenant, requestId, ref);
            return ref;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) throw new Problem(503, "WHATSAPP_RATE_LIMITED");
            throw new Problem(422, "WHATSAPP_PROVIDER_REJECTED");
        } catch (Exception e) {
            throw new Problem(503, "WHATSAPP_DELIVERY_UNCERTAIN");
        }
    }

    private RestClient client(String base) {
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    private void beforeSend(UUID tenant, UUID request, String channel, String recipient) {
        byte[] lookup = crypto.lookup(tenant, recipient);
        Problem.require(
                !db.exists(
                        "select 1 from app.communication_suppressions where tenant_id=? and"
                            + " channel=? and recipient_hmac=?",
                        tenant,
                        channel,
                        lookup),
                422,
                "RECIPIENT_SUPPRESSED");
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,21))",
                Object.class,
                tenant + ":" + java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")));
        int limit =
                db.optional(
                                "select payload from app.tenant_settings_versions where tenant_id=?"
                                    + " order by version desc limit 1",
                                tenant)
                        .map(
                                r ->
                                        parse(r.get("payload").toString())
                                                .path("communicationDailyLimit")
                                                .asInt(1000))
                        .orElse(1000);
        if (!db.exists(
                "select 1 from app.communication_sends where tenant_id=? and request_id=?",
                tenant,
                request))
            Problem.require(
                    db.jdbc.queryForObject(
                                    "select count(*) from app.communication_sends where tenant_id=?"
                                        + " and created_at>=date_trunc('day',now() at time zone"
                                        + " 'America/Sao_Paulo') at time zone 'America/Sao_Paulo'",
                                    Long.class,
                                    tenant)
                            < limit,
                    429,
                    "CAMPAIGN_DAILY_LIMIT");
        db.exec(
                "insert into app.communication_sends(tenant_id,request_id,channel,recipient_hmac)"
                    + " values(?,?,?,?) on conflict do nothing",
                tenant,
                request,
                channel,
                lookup);
    }

    private void sent(UUID tenant, UUID request, String ref) {
        db.exec(
                "update app.communication_sends set"
                    + " provider_message_ref=?,delivery_state=coalesce((select status from"
                    + " app.communication_events where tenant_id=? and message_ref=? order by case"
                    + " status when 'SUPPRESSED' then 4 when 'DELIVERED' then 3 when 'FAILED' then"
                    + " 2 else 1 end desc limit 1),'SENT') where tenant_id=? and request_id=?",
                ref,
                tenant,
                ref,
                tenant,
                request);
        db.exec(
                "insert into"
                    + " app.communication_suppressions(tenant_id,channel,recipient_hmac,reason_code)"
                    + " select tenant_id,channel,recipient_hmac,'BOUNCE_OR_COMPLAINT' from"
                    + " app.communication_sends where tenant_id=? and request_id=? and"
                    + " delivery_state='SUPPRESSED' on conflict do nothing",
                tenant,
                request);
    }
}
