package com.psicocare.content;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class ContentModule implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Crypto crypto;
    private final Events events;

    public ContentModule(Store db, Access access, Crypto crypto, Events events) {
        this.db = db;
        this.access = access;
        this.crypto = crypto;
        this.events = events;
    }

    public boolean supports(Command c) {
        if (c.tag().equals("ContentMetrics")) return false;
        return c.tag().equals("Content")
                || c.path().contains("/public/blog/")
                || c.path().contains("/public/newsletter/")
                || c.path().endsWith("/portal/materials")
                || c.path().endsWith("/portal/preferences");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        String path = c.path();
        if (path.contains("/public/blog/")) {
            UUID t =
                    id(
                            db.one(
                                    "select id from app.organizations where slug=? and"
                                            + " status='ACTIVE'",
                                    c.vars().get("orgSlug")),
                            "id");
            context(t);
            return c.page(
                    db
                            .rows(
                                    "select * from app.content_entries where tenant_id=? and"
                                            + " entry_type='BLOG' and status='PUBLISHED' and"
                                            + " visibility='PUBLIC' order by published_at desc",
                                    t)
                            .stream()
                            .map(this::content)
                            .toList());
        }
        if (path.contains("/public/newsletter/")) return newsletter(c);
        if (path.endsWith("/preferences")) {
            Problem.require(
                    db.exists(
                            "select 1 from app.patients where tenant_id=? and portal_user_id=?",
                            tenant,
                            a.user()),
                    404,
                    "RESOURCE_NOT_FOUND");
            var prefs =
                    db.optional(
                            "select * from app.user_notification_preferences where tenant_id=? and"
                                    + " user_id=? for update",
                            tenant,
                            a.user());
            int version = prefs.map(Store::version).orElse(1);
            if (c.isGet())
                return prefs.map(
                                r ->
                                        dto(
                                                r,
                                                "marketing_email",
                                                "marketing_whatsapp",
                                                "appointment_email",
                                                "appointment_whatsapp",
                                                "version"))
                        .orElse(
                                object(
                                        "marketingEmail",
                                        false,
                                        "marketingWhatsapp",
                                        false,
                                        "appointmentEmail",
                                        true,
                                        "appointmentWhatsapp",
                                        false,
                                        "version",
                                        1));
            Problem.require(c.expectedVersion() == version, 412, "STALE_VERSION");
            boolean
                    me =
                            b.path("marketingEmail")
                                    .asBoolean(
                                            prefs.map(r -> (Boolean) r.get("marketing_email"))
                                                    .orElse(false)),
                    mw =
                            b.path("marketingWhatsapp")
                                    .asBoolean(
                                            prefs.map(r -> (Boolean) r.get("marketing_whatsapp"))
                                                    .orElse(false)),
                    ae =
                            b.path("appointmentEmail")
                                    .asBoolean(
                                            prefs.map(r -> (Boolean) r.get("appointment_email"))
                                                    .orElse(true)),
                    aw =
                            b.path("appointmentWhatsapp")
                                    .asBoolean(
                                            prefs.map(r -> (Boolean) r.get("appointment_whatsapp"))
                                                    .orElse(false));
            db.exec(
                    "insert into"
                        + " app.user_notification_preferences(tenant_id,user_id,marketing_email,marketing_whatsapp,appointment_email,appointment_whatsapp,version)"
                        + " values(?,?,?,?,?,?,?) on conflict(tenant_id,user_id) do update set"
                        + " marketing_email=excluded.marketing_email,marketing_whatsapp=excluded.marketing_whatsapp,appointment_email=excluded.appointment_email,appointment_whatsapp=excluded.appointment_whatsapp,version=excluded.version,updated_at=now()",
                    tenant,
                    a.user(),
                    me,
                    mw,
                    ae,
                    aw,
                    version + 1);
            if (b.has("marketingEmail")) {
                db.exec(
                        "insert into"
                            + " app.consents(tenant_id,user_id,purpose_code,notice_version,law_basis_code,action,source)"
                            + " values(?,?,'MARKETING_EMAIL',?,'CONSENT',?,'PORTAL')",
                        tenant,
                        a.user(),
                        b.path("noticeVersion").asText("communications-v1"),
                        me ? "ACCEPT" : "WITHDRAW");
                if (!me)
                    db.exec(
                            "update app.newsletter_subscriptions set"
                                + " unsubscribed_at=coalesce(unsubscribed_at,now()) where"
                                + " tenant_id=? and email_lookup_hmac in(select email_lookup_hmac"
                                + " from app.patients where tenant_id=? and portal_user_id=?)",
                            tenant,
                            tenant,
                            a.user());
                else
                    for (var p :
                            db.rows(
                                    "select id,email_cipher,version from app.patients where"
                                        + " tenant_id=? and portal_user_id=? and email_cipher is"
                                        + " not null",
                                    tenant,
                                    a.user())) {
                        String
                                email =
                                        crypto.decrypt(
                                                tenant,
                                                "patients.email",
                                                id(p, "id"),
                                                version(p),
                                                (byte[]) p.get("email_cipher")),
                                slug =
                                        str(
                                                db.one(
                                                        "select slug from app.organizations where"
                                                                + " id=?",
                                                        tenant),
                                                "slug");
                        newsletter(
                                new Command(
                                        "POST",
                                        "/v1/public/newsletter/subscribe",
                                        "Content",
                                        "PUBLIC",
                                        Map.of(),
                                        Map.of(),
                                        object(
                                                "orgSlug",
                                                slug,
                                                "email",
                                                email,
                                                "noticeVersion",
                                                b.path("noticeVersion")
                                                        .asText("communications-v1")),
                                        a,
                                        0,
                                        c.verifiedEmail(),
                                        UUID.randomUUID()));
                    }
            }
            events.audit(a, "NotificationPreferencesUpdated", "user", a.user());
            return object(
                    "marketingEmail",
                    me,
                    "marketingWhatsapp",
                    mw,
                    "appointmentEmail",
                    ae,
                    "appointmentWhatsapp",
                    aw,
                    "version",
                    version + 1);
        }
        if (c.vars().containsKey("contentId")) {
            UUID id = c.id("contentId");
            var row =
                    db.one(
                            "select * from app.content_entries where tenant_id=? and id=?"
                                    + (c.isGet() ? "" : " for update"),
                            tenant,
                            id);
            if (c.isGet()) {
                visible(a, row);
                return content(row);
            }
            if (path.endsWith("/shares")) {
                UUID patient = uuid(b, "patientId");
                Problem.require(
                        a.has("OWNER", "ADMIN") || access.clinical(a, patient),
                        404,
                        "RESOURCE_NOT_FOUND");
                Problem.require(
                        str(row, "status").equals("PUBLISHED")
                                && str(row, "entry_type").equals("MATERIAL")
                                && Set.of("PATIENT_SHARED", "PATIENT_ASSIGNED")
                                        .contains(str(row, "visibility")),
                        409,
                        "INVALID_TRANSITION");
                UUID share = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.content_shares(tenant_id,id,content_id,patient_id,shared_by,valid_until)"
                            + " values(?,?,?,?,?,?) on conflict(tenant_id,content_id,patient_id) do"
                            + " update set valid_until=excluded.valid_until",
                        tenant,
                        share,
                        id,
                        patient,
                        a.user(),
                        b.has("validUntil") ? OffsetDateTime.parse(text(b, "validUntil")) : null);
                events.emitForPatient(a, "MaterialShared", "content", id, version(row), patient);
                return object("id", share, "version", 1);
            }
            a.require("OWNER", "ADMIN");
            String status = str(row, "status");
            if (c.method().equals("PATCH")) {
                c.version(row);
                Problem.require(
                        Set.of("DRAFT", "REVIEW").contains(status), 409, "IMMUTABLE_RESOURCE");
                db.exec(
                        "update app.content_entries set"
                            + " title=?,body_sanitized_html=?,visibility=?,slug=?,status='DRAFT',version=version+1"
                            + " where tenant_id=? and id=?",
                        text(b, "title"),
                        sanitize(text(b, "bodyHtml")),
                        text(b, "visibility"),
                        text(b, "slug").isBlank() ? null : text(b, "slug"),
                        tenant,
                        id);
            } else {
                String target =
                        switch (c.tail()) {
                            case "review" -> "REVIEW";
                            case "publish" -> "PUBLISHED";
                            case "archive" -> "ARCHIVED";
                            default -> throw Problem.notFound();
                        };
                if (target.equals("REVIEW"))
                    Problem.require(status.equals("DRAFT"), 409, "INVALID_TRANSITION");
                if (target.equals("PUBLISHED")) {
                    Problem.require(status.equals("REVIEW"), 409, "CONTENT_REVIEW_REQUIRED");
                    validateMarketing(str(row, "body_sanitized_html"));
                }
                if (target.equals("ARCHIVED"))
                    Problem.require(!status.equals("ARCHIVED"), 409, "INVALID_TRANSITION");
                db.exec(
                        "update app.content_entries set status=?,published_at=case when"
                            + " ?='PUBLISHED' then now() else published_at end,version=version+1"
                            + " where tenant_id=? and id=?",
                        target,
                        target,
                        tenant,
                        id);
                events.emit(a, "Content" + target, "content", id, version(row) + 1);
            }
            return content(
                    db.one(
                            "select * from app.content_entries where tenant_id=? and id=?",
                            tenant,
                            id));
        }
        if (c.isGet()) {
            boolean portal = path.contains("/portal/");
            List<Map<String, Object>> rows;
            if (!portal && a.has("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY", "BILLING"))
                rows =
                        db.rows(
                                "select * from app.content_entries where tenant_id=? order by"
                                        + " created_at desc",
                                tenant);
            else
                rows =
                        db.rows(
                                "select ce.* from app.content_entries ce join app.content_shares cs"
                                    + " on cs.tenant_id=ce.tenant_id and cs.content_id=ce.id join"
                                    + " app.patients p on p.tenant_id=cs.tenant_id and"
                                    + " p.id=cs.patient_id where ce.tenant_id=? and"
                                    + " p.portal_user_id=? and ce.status='PUBLISHED' and"
                                    + " (cs.valid_until is null or cs.valid_until>now()) order by"
                                    + " cs.created_at desc",
                                tenant,
                                a.user());
            return c.page(rows.stream().map(this::content).toList());
        }
        a.require("OWNER", "ADMIN");
        String body = sanitize(text(b, "bodyHtml"));
        validateMarketing(body);
        UUID id = UUID.randomUUID();
        if (b.has("fileId"))
            Problem.require(
                    db.exists(
                            "select 1 from app.file_assets where tenant_id=? and id=? and"
                                    + " status='READY' and classification in ('PUBLIC','INTERNAL')",
                            tenant,
                            uuid(b, "fileId")),
                    422,
                    "FILE_NOT_READY");
        db.exec(
                "insert into"
                    + " app.content_entries(tenant_id,id,entry_type,slug,status,title,body_sanitized_html,author_user_id,visibility,file_asset_id)"
                    + " values(?,?,?,?,'DRAFT',?,?,?,?,?)",
                tenant,
                id,
                text(b, "entryType"),
                text(b, "slug").isBlank() ? null : text(b, "slug"),
                text(b, "title"),
                body,
                a.user(),
                text(b, "visibility"),
                b.has("fileId") ? uuid(b, "fileId") : null);
        events.audit(a, "ContentCreated", "content", id);
        return content(
                db.one("select * from app.content_entries where tenant_id=? and id=?", tenant, id));
    }

    public static String sanitize(String html) {
        return Jsoup.clean(
                        html,
                        Safelist.basic()
                                .removeProtocols("a", "href", "ftp")
                                .addProtocols("a", "href", "https"))
                .replaceAll("(?i)target=\"_blank\"", "rel=\"noopener noreferrer\"");
    }

    public static void validateMarketing(String body) {
        String normalized = Jsoup.parse(body).text().toLowerCase(Locale.ROOT);
        Problem.require(
                !normalized.matches(
                        "(?s).*(\\b[0-9]{3}[.][0-9]{3}[.][0-9]{3}-[0-9]{2}\\b|\\b[0-9]{11}\\b|\\{\\{.*(narrative|diagnosis|cpf|prontuario|clinical).*[}]\u007d).*"),
                422,
                "SENSITIVE_TEMPLATE_FIELD");
    }

    private void visible(Actor a, Map<String, Object> r) {
        if (a.has("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY", "BILLING")) return;
        Problem.require(
                str(r, "status").equals("PUBLISHED")
                        && db.exists(
                                "select 1 from app.content_shares cs join app.patients p on"
                                    + " p.tenant_id=cs.tenant_id and p.id=cs.patient_id where"
                                    + " cs.tenant_id=? and cs.content_id=? and p.portal_user_id=?"
                                    + " and (cs.valid_until is null or cs.valid_until>now())",
                                a.tenant(),
                                r.get("id"),
                                a.user()),
                404,
                "RESOURCE_NOT_FOUND");
    }

    private JsonNode newsletter(Command c) {
        var b = c.body();
        boolean confirm = c.path().endsWith("/confirm");
        if (c.path().endsWith("/unsubscribe") || confirm) {
            byte[] tokenHash = hash(text(b, "token"));
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
                                    + " purpose=? and expires_at>now() for update",
                            tenant,
                            tokenHash,
                            confirm ? "NEWSLETTER_CONFIRM" : "UNSUBSCRIBE");
            var subscription =
                    db.one(
                            "select * from app.newsletter_subscriptions where tenant_id=? and id=?"
                                    + " for update",
                            tenant,
                            token.get("subscription_id"));
            Problem.require(
                    Objects.equals(
                            subscription.get("consent_generation"),
                            token.get("consent_generation")),
                    404,
                    "RESOURCE_NOT_FOUND");
            if (confirm) {
                Problem.require(
                        subscription.get("unsubscribed_at") == null, 409, "CONSENT_REVOKED");
                db.exec(
                        "update app.newsletter_subscriptions set"
                            + " verified_at=coalesce(verified_at,now()) where tenant_id=? and id=?",
                        tenant,
                        subscription.get("id"));
            } else
                db.exec(
                        "update app.newsletter_subscriptions set"
                            + " unsubscribed_at=coalesce(unsubscribed_at,now()) where tenant_id=?"
                            + " and id=?",
                        tenant,
                        subscription.get("id"));
            db.exec(
                    "update app.action_tokens set consumed_at=coalesce(consumed_at,now()) where"
                            + " tenant_id=? and id=?",
                    tenant,
                    token.get("id"));
            return object("id", token.get("id"), "version", 1);
        }
        UUID tenant =
                id(
                        db.one(
                                "select id from app.organizations where slug=? and status='ACTIVE'",
                                text(b, "orgSlug")),
                        "id");
        context(tenant);
        UUID id = UUID.randomUUID(), generation = UUID.randomUUID();
        byte[] lookup = crypto.lookup(tenant, text(b, "email"));
        db.jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,6))",
                Object.class,
                tenant + ":" + HexFormat.of().formatHex(lookup));
        var existing =
                db.optional(
                        "select * from app.newsletter_subscriptions where tenant_id=? and"
                                + " email_lookup_hmac=? for update",
                        tenant,
                        lookup);
        if (existing.isPresent()) {
            var r = existing.get();
            id = id(r, "id");
            if (r.get("unsubscribed_at") == null)
                return object("id", c.idempotencyKey(), "version", 1);
            db.exec(
                    "update app.newsletter_subscriptions set"
                        + " unsubscribed_at=null,verified_at=null,consent_generation=?,consent_at=now(),notice_version=?,proof_ref=?"
                        + " where tenant_id=? and id=?",
                    generation,
                    text(b, "noticeVersion"),
                    c.actor().requestId().toString(),
                    tenant,
                    id);
        } else
            db.exec(
                    "insert into"
                        + " app.newsletter_subscriptions(tenant_id,id,email_cipher,email_lookup_hmac,notice_version,proof_ref,consent_generation)"
                        + " values(?,?,?,?,?,?,?)",
                    tenant,
                    id,
                    crypto.encrypt(tenant, "newsletter_subscriptions", id, 1, text(b, "email")),
                    lookup,
                    text(b, "noticeVersion"),
                    c.actor().requestId().toString(),
                    generation);
        var nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        UUID token = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.action_tokens(tenant_id,id,token_hash,purpose,subscription_id,consent_generation,expires_at,delivery_cipher)"
                    + " values(?,?,?,'NEWSLETTER_CONFIRM',?,?,now()+interval '72 hours',?)",
                tenant,
                token,
                hash(raw),
                id,
                generation,
                crypto.encrypt(
                        tenant,
                        "action_tokens",
                        token,
                        1,
                        object("email", text(b, "email"), "token", raw).toString()));
        db.exec(
                "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                        + " values(?,'NEWSLETTER_CONFIRM',?,?::jsonb)",
                tenant,
                token.toString(),
                object("tokenId", token).toString());
        return object("id", c.idempotencyKey(), "version", 1);
    }

    private JsonNode content(Map<String, Object> r) {
        return dto(
                r,
                "id",
                "entry_type",
                "title",
                "status",
                "visibility",
                "body_sanitized_html",
                "slug",
                "published_at",
                "version",
                "file_asset_id");
    }

    private void context(UUID tenant) {
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
    }
}
