package com.psicocare.communications;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.http.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@RestController
public class DeliveryWebhookController {
    private final Store db;
    private final Crypto crypto;
    private final TransactionTemplate tx;

    public DeliveryWebhookController(Store db, Crypto crypto, TransactionTemplate tx) {
        this.db = db;
        this.crypto = crypto;
        this.tx = tx;
    }

    @GetMapping("/v1/webhooks/communications/{connectionId}")
    public ResponseEntity<String> verify(
            @PathVariable UUID connectionId,
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        return tx.execute(
                status -> {
                    var config = connection(connectionId);
                    var secret = crypto.secret(str(config, "secrets_path"));
                    Problem.require(
                            str(config, "provider").equals("WHATSAPP")
                                    && mode.equals("subscribe")
                                    && text(secret, "verifyToken").length() >= 32
                                    && same(token, text(secret, "verifyToken")),
                            403,
                            "INVALID_WEBHOOK_SECRET");
                    Problem.require(
                            challenge.matches("[A-Za-z0-9_-]{1,200}"), 422, "INVALID_CHALLENGE");
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(challenge);
                });
    }

    @PostMapping("/v1/webhooks/communications/{connectionId}")
    public ResponseEntity<JsonNode> receive(
            @PathVariable UUID connectionId,
            @RequestHeader HttpHeaders headers,
            @RequestBody byte[] raw) {
        Problem.require(raw.length <= 262144, 413, "REQUEST_TOO_LARGE");
        return tx.execute(
                status -> {
                    var config = connection(connectionId);
                    var secret = crypto.secret(str(config, "secrets_path"));
                    String provider = str(config, "provider");
                    var t = id(config, "tenant_id");
                    Problem.require(
                            authenticate(provider, secret, headers, raw, Instant.now()),
                            401,
                            "INVALID_WEBHOOK_SECRET");
                    JsonNode body = parse(new String(raw, StandardCharsets.UTF_8));
                    if (provider.equals("EMAIL")) {
                        String state = emailState(text(body, "type"));
                        if (state != null)
                            apply(
                                    t,
                                    provider,
                                    headers.getFirst("svix-id"),
                                    text(body.path("data"), "email_id"),
                                    state,
                                    null);
                    } else {
                        for (var entry : body.path("entry"))
                            for (var change : entry.path("changes")) {
                                var value = change.path("value");
                                Problem.require(
                                        text(value.path("metadata"), "phone_number_id")
                                                .equals(text(secret, "phoneNumberId")),
                                        401,
                                        "WEBHOOK_ACCOUNT_MISMATCH");
                                for (var event : value.path("statuses")) {
                                    String state =
                                            switch (text(event, "status")) {
                                                case "sent" -> "SENT";
                                                case "delivered", "read" -> "DELIVERED";
                                                case "failed" -> "FAILED";
                                                default -> null;
                                            };
                                    if (state != null)
                                        apply(
                                                t,
                                                provider,
                                                HexFormat.of()
                                                        .formatHex(
                                                                hash(
                                                                        text(event, "id")
                                                                                + ":"
                                                                                + text(
                                                                                        event,
                                                                                        "timestamp")
                                                                                + ":"
                                                                                + state)),
                                                text(event, "id"),
                                                state,
                                                text(event, "biz_opaque_callback_data"));
                                }
                            }
                    }
                    return ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(object("accepted", true));
                });
    }

    private Map<String, Object> connection(UUID id) {
        var route =
                db.one(
                        "select * from app.integration_routes where id=? and provider in"
                            + " ('EMAIL','WHATSAPP')",
                        id);
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)",
                String.class,
                route.get("tenant_id").toString());
        db.jdbc.queryForObject("select set_config('app.actor_id','',true)", String.class);
        return db.one(
                "select * from app.integration_connections where tenant_id=? and"
                    + " webhook_identifier=? and status='ACTIVE'",
                route.get("tenant_id"),
                id);
    }

    public static boolean authenticate(
            String provider, JsonNode secret, HttpHeaders headers, byte[] raw, Instant now) {
        try {
            if (provider.equals("EMAIL")) {
                String id = headers.getFirst("svix-id"),
                        timestamp = headers.getFirst("svix-timestamp"),
                        signature = headers.getFirst("svix-signature");
                if (id == null
                        || id.length() > 200
                        || timestamp == null
                        || signature == null
                        || Math.abs(now.getEpochSecond() - Long.parseLong(timestamp)) > 300)
                    return false;
                String key = text(secret, "webhookSecret");
                if (!key.startsWith("whsec_")) return false;
                var signed = new java.io.ByteArrayOutputStream();
                signed.write((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
                signed.write(raw);
                byte[] expected =
                        hmac(Base64.getDecoder().decode(key.substring(6)), signed.toByteArray());
                for (String candidate : signature.split(" "))
                    if (candidate.startsWith("v1,")
                            && MessageDigest.isEqual(
                                    expected, Base64.getDecoder().decode(candidate.substring(3))))
                        return true;
                return false;
            }
            String signature = headers.getFirst("X-Hub-Signature-256");
            String key = text(secret, "appSecret");
            return key.length() >= 32
                    && signature != null
                    && signature.startsWith("sha256=")
                    && MessageDigest.isEqual(
                            hmac(key.getBytes(StandardCharsets.UTF_8), raw),
                            HexFormat.of().parseHex(signature.substring(7)));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String emailState(String type) {
        return switch (type) {
            case "email.sent" -> "SENT";
            case "email.delivered" -> "DELIVERED";
            case "email.bounced", "email.complained", "email.suppressed" -> "SUPPRESSED";
            case "email.failed" -> "FAILED";
            default -> null;
        };
    }

    private void apply(
            UUID t, String provider, String event, String ref, String state, String requestRef) {
        Problem.require(
                event != null
                        && !event.isBlank()
                        && event.length() <= 200
                        && !ref.isBlank()
                        && ref.length() <= 300,
                422,
                "INVALID_PROVIDER_EVENT");
        if (db.exec(
                        "insert into"
                            + " app.communication_events(tenant_id,provider,event_id,message_ref,status)"
                            + " values(?,?,?,?,?) on conflict do nothing",
                        t,
                        provider,
                        event,
                        ref,
                        state)
                == 0) return;
        db.exec(
                "update app.communication_sends set delivery_state=? where tenant_id=? and"
                    + " provider_message_ref=? and delivery_state<>'SUPPRESSED' and"
                    + " not(delivery_state='DELIVERED' and ?<>'SUPPRESSED') and"
                    + " not(delivery_state='FAILED' and ?='SENT')",
                state,
                t,
                ref,
                state,
                state);
        db.exec(
                "update app.notifications set status=?::app.notification_status,delivered_at=case"
                    + " when ?='DELIVERED' then coalesce(delivered_at,now()) else delivered_at"
                    + " end,failure_code=case when ? in ('FAILED','SUPPRESSED') then"
                    + " 'PROVIDER_CALLBACK' else failure_code end where tenant_id=? and"
                    + " (provider_message_ref=? or request_reference::text=?) and"
                    + " status<>'SUPPRESSED' and not(status='DELIVERED' and ?<>'SUPPRESSED') and"
                    + " not(status='FAILED' and ?='SENT')",
                state,
                state,
                state,
                t,
                ref,
                requestRef,
                state,
                state);
        if (state.equals("SUPPRESSED")) {
            for (var send :
                    db.rows(
                            "select * from app.communication_sends where tenant_id=? and"
                                + " provider_message_ref=?",
                            t,
                            ref))
                db.exec(
                        "insert into"
                            + " app.communication_suppressions(tenant_id,channel,recipient_hmac,reason_code)"
                            + " values(?,?,?,'BOUNCE_OR_COMPLAINT') on conflict do nothing",
                        t,
                        send.get("channel"),
                        send.get("recipient_hmac"));
        }
    }
}
