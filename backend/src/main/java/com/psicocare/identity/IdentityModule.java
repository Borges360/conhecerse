package com.psicocare.identity;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class IdentityModule implements ModuleHandler {
    private final Store db;
    private final Crypto crypto;
    private final Events events;

    public IdentityModule(Store db, Crypto crypto, Events events) {
        this.db = db;
        this.crypto = crypto;
        this.events = events;
    }

    public boolean supports(Command c) {
        return Set.of("Identity", "Tenancy", "Professionals", "OrganizationHistory")
                        .contains(c.tag())
                || (c.tag().equals("Public") && c.path().contains("professionals"));
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        var b = c.body();
        UUID tenant = a.tenant();
        String path = c.path();
        if (path.equals("/v1/me/organizations")) {
            var list = new ArrayList<JsonNode>();
            for (var r :
                    db.rows(
                            "select o.* from app.organizations o join app.user_tenant_index i on"
                                + " i.tenant_id=o.id where i.user_id=? and o.status='ACTIVE' order"
                                + " by o.display_name",
                            a.user())) {
                UUID t = id(r, "id");
                context(t);
                if (db.exists(
                        "select 1 from app.organization_memberships where tenant_id=? and user_id=?"
                            + " and status='ACTIVE' union all select 1 from app.patients where"
                            + " tenant_id=? and portal_user_id=? union all select 1 from"
                            + " app.guardian_patient_access where tenant_id=? and"
                            + " guardian_user_id=? and legal_verified_at is not null and not"
                            + " review_required and valid_from<=now() and (valid_until is null or"
                            + " valid_until>now())",
                        t,
                        a.user(),
                        t,
                        a.user(),
                        t,
                        a.user())) list.add(organization(r));
            }
            return page(list);
        }
        if (path.equals("/v1/orgs")) {
            Problem.require(c.verifiedEmail() != null, 403, "VERIFIED_EMAIL_REQUIRED");
            a.recentMfa();
            db.jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?,4))",
                    Object.class,
                    a.user() + ":" + c.idempotencyKey());
            var prior =
                    db.optional(
                            "select * from app.bootstrap_idempotency where actor_user_id=? and"
                                    + " idem_key=?",
                            a.user(),
                            c.idempotencyKey());
            if (prior.isPresent()) {
                Problem.require(
                        java.security.MessageDigest.isEqual(
                                (byte[]) prior.get().get("body_hash"), hash(canonical(b))),
                        409,
                        "IDEMPOTENCY_MISMATCH");
                return organization(
                        db.one(
                                "select * from app.organizations where id=?",
                                prior.get().get("organization_id")));
            }
            String tz = b.path("timezone").asText("America/Sao_Paulo");
            zone(tz);
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into app.organizations(id,slug,display_name,tax_person_type,timezone)"
                            + " values(?,?,?,?,?)",
                    id,
                    text(b, "slug"),
                    text(b, "displayName"),
                    text(b, "legalPersonType"),
                    tz);
            context(id);
            saveRegistration(id, b);
            db.exec(
                    "insert into app.organization_memberships(tenant_id,user_id,roles,joined_at)"
                            + " values(?,?,?::app.member_role[],now())",
                    id,
                    a.user(),
                    b.path("ownerIsPsychologist").asBoolean() ? "{OWNER,PSYCHOLOGIST}" : "{OWNER}");
            UUID worker =
                    db.jdbc.queryForObject("select app.ensure_user(?)", UUID.class, "worker-" + id);
            db.exec(
                    "insert into"
                        + " app.organization_memberships(tenant_id,user_id,roles,joined_at,service_account)"
                        + " values(?,?,array['BILLING','SECRETARY']::app.member_role[],now(),true)",
                    id,
                    worker);
            db.exec("update app.organizations set worker_user_id=? where id=?", worker, id);
            var ownerMembership =
                    db.one(
                            "select id from app.organization_memberships where tenant_id=? and"
                                    + " user_id=?",
                            id,
                            a.user());
            db.exec(
                    "update app.organization_memberships set email_cipher=? where tenant_id=? and"
                            + " user_id=?",
                    crypto.encrypt(
                            id,
                            "organization_memberships.email",
                            id(ownerMembership, "id"),
                            1,
                            c.verifiedEmail()),
                    id,
                    a.user());
            db.exec(
                    "insert into app.bootstrap_idempotency values(?,?,?,?)",
                    a.user(),
                    c.idempotencyKey(),
                    hash(canonical(b)),
                    id);
            events.audit(
                    new Actor(id, a.user(), Set.of("OWNER"), a.mfaAt(), a.requestId()),
                    "OrganizationCreated",
                    "organization",
                    id);
            return organization(db.one("select * from app.organizations where id=?", id));
        }
        if (path.equals("/v1/invitations/accept")) return acceptInvite(c);
        if (c.tag().equals("Public")) {
            String where = "verification_status='VERIFIED' and public_profile_enabled";
            if (c.vars().containsKey("professionalId"))
                return professional(
                        db.one(
                                "select * from app.professionals where id=? and " + where,
                                c.id("professionalId")));
            return c.page(
                    db
                            .rows(
                                    "select p.* from app.professionals p join app.organizations o"
                                        + " on p.tenant_id=o.id where"
                                        + " p.verification_status='VERIFIED' and"
                                        + " p.public_profile_enabled and o.status='ACTIVE' order by"
                                        + " p.display_name")
                            .stream()
                            .map(this::professional)
                            .toList());
        }
        if (c.tag().equals("OrganizationHistory")) {
            a.require("OWNER", "ADMIN");
            return c.page(
                    db
                            .rows(
                                    "select version,payload,created_at from"
                                        + " app.tenant_settings_versions where tenant_id=? order by"
                                        + " version desc",
                                    tenant)
                            .stream()
                            .map(
                                    r ->
                                            object(
                                                    "version",
                                                    r.get("version"),
                                                    "policy",
                                                    parse(r.get("payload").toString()),
                                                    "createdAt",
                                                    r.get("created_at").toString()))
                            .toList());
        }
        if (path.endsWith("/settings")) {
            if (c.isGet()) return settings(tenant);
            var org = db.one("select * from app.organizations where id=? for update", tenant);
            Problem.require(
                    c.expectedVersion() == ((Number) org.get("settings_version")).intValue(),
                    412,
                    "STALE_VERSION");
            var next = (tools.jackson.databind.node.ObjectNode) settings(tenant);
            b.properties().forEach(f -> next.set(f.getKey(), f.getValue()));
            for (JsonNode hours : next.path("openingHours"))
                Problem.require(
                        LocalTime.parse(text(hours, "start"))
                                .isBefore(LocalTime.parse(text(hours, "end"))),
                        422,
                        "INVALID_OPENING_HOURS");
            if (b.has("defaultTimezone")) zone(b.get("defaultTimezone").asText());
            if (b.path("cancelChargePercent").asInt() > 0)
                Problem.require(
                        db.exists(
                                "select 1 from app.release_approvals where tenant_id=? and"
                                        + " gate_code='GATE-BUSINESS-01'",
                                tenant),
                        422,
                        "CONSENT_REQUIRED");
            int v = ((Number) org.get("settings_version")).intValue() + 1;
            db.exec(
                    "insert into app.tenant_settings_versions(tenant_id,version,payload,created_by)"
                            + " values(?,?,?::jsonb,?)",
                    tenant,
                    v,
                    next.toString(),
                    a.user());
            db.exec(
                    "update app.organizations set settings_version=?,updated_at=now() where id=?",
                    v,
                    tenant);
            events.audit(a, "SettingsVersioned", "organization", tenant);
            return next;
        }
        if (c.vars().containsKey("memberId")) {
            a.require("OWNER", "ADMIN");
            a.recentMfa();
            UUID id = c.id("memberId");
            db.jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?,7))",
                    Object.class,
                    tenant.toString());
            var m =
                    db.one(
                            "select *,roles::text as role_text from app.organization_memberships"
                                + " where tenant_id=? and id=? and not service_account for update",
                            tenant,
                            id);
            c.version(m);
            String[] roles = Json.MAPPER.convertValue(b.path("roles"), String[].class);
            boolean wasOwner = str(m, "role_text").contains("OWNER"),
                    keepsOwner =
                            Arrays.asList(roles).contains("OWNER")
                                    && text(b, "status").equals("ACTIVE");
            if (wasOwner && !keepsOwner)
                Problem.require(
                        db.exists(
                                "select 1 from app.organization_memberships where tenant_id=? and"
                                        + " id<>? and status='ACTIVE' and 'OWNER'=any(roles)",
                                tenant,
                                id),
                        409,
                        "LAST_OWNER_REQUIRED");
            if (Arrays.asList(roles).contains("OWNER") || wasOwner)
                Problem.require(a.has("OWNER"), 403, "OWNER_REQUIRED");
            Problem.require(!str(m, "status").equals("INVITED"), 409, "INVITATION_PENDING");
            db.exec(
                    "update app.organization_memberships set"
                        + " status=?,roles=?::app.member_role[],version=version+1 where tenant_id=?"
                        + " and id=?",
                    text(b, "status"),
                    "{" + String.join(",", roles) + "}",
                    tenant,
                    id);
            events.audit(a, "MembershipPermissionsUpdated", "membership", id);
            return object("id", id, "version", version(m) + 1);
        }
        if (path.endsWith("/members/invitations")) {
            UUID pending =
                    db.jdbc.queryForObject(
                            "select app.ensure_user(?)",
                            UUID.class,
                            "pending-" + UUID.randomUUID());
            UUID member = UUID.randomUUID();
            String[] roles = Json.MAPPER.convertValue(b.get("roles"), String[].class);
            db.exec(
                    "insert into app.organization_memberships(tenant_id,id,user_id,roles,status)"
                            + " values(?,?,?,?::app.member_role[],'INVITED')",
                    tenant,
                    member,
                    pending,
                    "{" + String.join(",", roles) + "}");
            invite(a, null, member, text(b, "email"));
            return object("id", member, "version", 1);
        }
        if (path.endsWith("/members"))
            return c.page(
                    db
                            .rows(
                                    "select id,user_id,roles::text,status,version,created_at from"
                                        + " app.organization_memberships where tenant_id=? and not"
                                        + " service_account order by created_at",
                                    tenant)
                            .stream()
                            .map(r -> dto(r, "id", "user_id", "roles", "status", "version"))
                            .toList());
        if (c.tag().equals("Professionals")) {
            if (c.method().equals("PATCH")) {
                UUID id = c.id("professionalId");
                var row =
                        db.one(
                                "select * from app.professionals where tenant_id=? and id=? for"
                                    + " update",
                                tenant,
                                id);
                c.version(row);
                var attributes =
                        (tools.jackson.databind.node.ObjectNode)
                                parse(str(row, "public_attributes"));
                b.properties().forEach(f -> attributes.set(f.getKey(), f.getValue()));
                db.exec(
                        "update app.professionals set"
                            + " public_attributes=?::jsonb,public_profile_enabled=?,specialties_reviewed=case"
                            + " when ? then '[]'::jsonb else specialties_reviewed"
                            + " end,version=version+1 where tenant_id=? and id=?",
                        attributes.toString(),
                        b.path("publicProfileEnabled")
                                .asBoolean(Boolean.TRUE.equals(row.get("public_profile_enabled"))),
                        b.has("specialties"),
                        tenant,
                        id);
                events.audit(a, "ProfessionalProfileUpdated", "professional", id);
                return professional(
                        db.one(
                                "select * from app.professionals where tenant_id=? and id=?",
                                tenant,
                                id));
            }
            if (path.endsWith("/review")) {
                a.require("OWNER", "ADMIN");
                UUID id = c.id("professionalId");
                var profile =
                        db.one(
                                "select * from app.professionals where tenant_id=? and id=? for"
                                    + " update",
                                tenant,
                                id);
                JsonNode declared = parse(str(profile, "public_attributes")).path("specialties");
                for (JsonNode specialty : b.path("reviewedSpecialties")) {
                    boolean found = false;
                    for (JsonNode s : declared) if (s.equals(specialty)) found = true;
                    Problem.require(found, 422, "SPECIALTY_NOT_DECLARED");
                }
                db.exec(
                        "update app.professionals set specialties_reviewed=?::jsonb where"
                            + " tenant_id=? and id=?",
                        b.path("reviewedSpecialties").isMissingNode()
                                ? "[]"
                                : b.path("reviewedSpecialties").toString(),
                        tenant,
                        id);
                db.exec(
                        "update app.professionals set"
                            + " verification_status=?,verification_reviewed_by=?,verification_evidence=?,version=version+1"
                            + " where tenant_id=? and id=?",
                        text(b, "status"),
                        a.user(),
                        text(b, "evidence"),
                        tenant,
                        id);
                events.audit(a, "ProfessionalReviewed", "professional", id);
                return professional(
                        db.one(
                                "select * from app.professionals where tenant_id=? and id=?",
                                tenant,
                                id));
            }
            if (c.isGet()) {
                if (c.vars().containsKey("professionalId"))
                    return professional(
                            db.one(
                                    "select * from app.professionals where tenant_id=? and id=?",
                                    tenant,
                                    c.id("professionalId")));
                return c.page(
                        db
                                .rows(
                                        "select * from app.professionals where tenant_id=? order by"
                                                + " display_name",
                                        tenant)
                                .stream()
                                .map(this::professional)
                                .toList());
            }
            UUID user = uuid(b, "userId");
            Problem.require(
                    db.exists(
                            "select 1 from app.organization_memberships where tenant_id=? and"
                                + " user_id=? and status='ACTIVE' and 'PSYCHOLOGIST'=any(roles)",
                            tenant,
                            user),
                    422,
                    "ACTIVE_CLINICIAN_MEMBERSHIP_REQUIRED");
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.professionals(tenant_id,id,user_id,display_name,crp_uf,crp_number,public_profile_enabled)"
                        + " values(?,?,?,?,?,?,?)",
                    tenant,
                    id,
                    user,
                    text(b, "displayName"),
                    text(b, "crpUf"),
                    text(b, "crpNumber"),
                    b.path("publicProfileEnabled").asBoolean(false));
            var attributes = object();
            for (String field :
                    List.of("languages", "specialties", "appointmentTypes", "publicBio"))
                if (b.has(field)) attributes.set(field, b.get(field));
            db.exec(
                    "update app.professionals set public_attributes=?::jsonb where tenant_id=? and"
                        + " id=?",
                    attributes.toString(),
                    tenant,
                    id);
            events.audit(a, "ProfessionalCreated", "professional", id);
            return professional(
                    db.one(
                            "select * from app.professionals where tenant_id=? and id=?",
                            tenant,
                            id));
        }
        var org = db.one("select * from app.organizations where id=?", tenant);
        if (c.isGet()) return organization(org);
        Problem.require(
                c.expectedVersion() == ((Number) org.get("settings_version")).intValue(),
                412,
                "STALE_VERSION");
        if (b.has("timezone")) zone(text(b, "timezone"));
        saveRegistration(tenant, b);
        db.exec(
                "update app.organizations set"
                    + " display_name=?,timezone=?,status=?,settings_version=settings_version+1,updated_at=now()"
                    + " where id=?",
                b.path("displayName").asText(str(org, "display_name")),
                b.path("timezone").asText(str(org, "timezone")),
                b.path("status").asText(str(org, "status")),
                tenant);
        events.audit(a, "OrganizationUpdated", "organization", tenant);
        return organization(db.one("select * from app.organizations where id=?", tenant));
    }

    private JsonNode organization(Map<String, Object> r) {
        var result =
                object(
                        "id",
                        r.get("id"),
                        "displayName",
                        r.get("display_name"),
                        "timezone",
                        r.get("timezone"),
                        "status",
                        r.get("status"),
                        "settingsVersion",
                        r.get("settings_version"),
                        "version",
                        r.get("settings_version"));
        result.put("slug", str(r, "slug"));
        result.put("legalPersonType", str(r, "tax_person_type"));
        if (r.get("registration_cipher") != null
                && Boolean.TRUE.equals(
                        db.jdbc.queryForObject(
                                "select app.is_staff(array['OWNER','ADMIN'])", Boolean.class)))
            result.set(
                    "registration",
                    parse(
                            crypto.decrypt(
                                    id(r, "id"),
                                    "organizations.registration",
                                    id(r, "id"),
                                    1,
                                    (byte[]) r.get("registration_cipher"))));
        return result;
    }

    private void saveRegistration(UUID tenant, JsonNode body) {
        if (body.has("registration"))
            db.exec(
                    "update app.organizations set registration_cipher=? where id=?",
                    crypto.encrypt(
                            tenant,
                            "organizations.registration",
                            tenant,
                            1,
                            body.get("registration").toString()),
                    tenant);
    }

    private JsonNode professional(Map<String, Object> r) {
        var result =
                (tools.jackson.databind.node.ObjectNode)
                        dto(
                                r,
                                "id",
                                "user_id",
                                "display_name",
                                "crp_uf",
                                "crp_number",
                                "verification_status",
                                "public_profile_enabled",
                                "version");
        parse(str(r, "public_attributes"))
                .properties()
                .forEach(f -> result.set(f.getKey(), f.getValue()));
        result.set("reviewedSpecialties", parse(str(r, "specialties_reviewed")));
        return result;
    }

    private JsonNode settings(UUID tenant) {
        return db.optional(
                        "select payload from app.tenant_settings_versions where tenant_id=? order"
                                + " by version desc limit 1",
                        tenant)
                .map(r -> parse(r.get("payload").toString()))
                .orElse(
                        object(
                                "minNoticeHours",
                                24,
                                "cancelChargePercent",
                                0,
                                "defaultTimezone",
                                "America/Sao_Paulo",
                                "emailEnabled",
                                true,
                                "whatsappEnabled",
                                false));
    }

    private void context(UUID tenant) {
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
    }

    private void zone(String name) {
        try {
            ZoneId.of(name);
        } catch (Exception e) {
            throw new Problem(422, "INVALID_TIMEZONE");
        }
    }

    private void invite(Actor a, UUID patient, UUID member, String email) {
        var raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into"
                    + " app.action_tokens(tenant_id,id,token_hash,purpose,patient_id,membership_id,email_lookup_hmac,delivery_cipher,expires_at)"
                    + " values(?,?,?,'INVITE',?,?,?,?,now()+interval '72 hours')",
                a.tenant(),
                id,
                hash(token),
                patient,
                member,
                crypto.lookup(a.tenant(), email),
                crypto.encrypt(
                        a.tenant(),
                        "action_tokens",
                        id,
                        1,
                        object("token", token, "email", email).toString()));
        db.exec(
                "insert into app.jobs(tenant_id,job_type,natural_key,payload)"
                        + " values(?,'INVITE',?,?::jsonb)",
                a.tenant(),
                id.toString(),
                object("tokenId", id).toString());
        events.audit(a, "MemberInvited", "membership", member);
    }

    private JsonNode acceptInvite(Command c) {
        Problem.require(c.verifiedEmail() != null, 403, "VERIFIED_EMAIL_REQUIRED");
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
                            + " purpose='INVITE' and expires_at>now() and consumed_at is null for"
                            + " update",
                        tenant,
                        tokenHash);
        Problem.require(
                java.security.MessageDigest.isEqual(
                        crypto.lookup(tenant, c.verifiedEmail()),
                        (byte[]) token.get("email_lookup_hmac")),
                404,
                "RESOURCE_NOT_FOUND");
        if (token.get("patient_id") != null) {
            db.jdbc.queryForObject(
                    "select set_config('app.invitation_patient_id',?,true)",
                    String.class,
                    token.get("patient_id").toString());
            db.exec(
                    "update app.patients set portal_user_id=? where tenant_id=? and id=? and"
                            + " portal_user_id is null",
                    c.actor().user(),
                    tenant,
                    token.get("patient_id"));
        }
        if (token.get("membership_id") != null) {
            c.actor().recentMfa();
            db.exec(
                    "update app.organization_memberships set"
                            + " user_id=?,status='ACTIVE',joined_at=now(),email_cipher=? where"
                            + " tenant_id=? and id=? and status='INVITED'",
                    c.actor().user(),
                    crypto.encrypt(
                            tenant,
                            "organization_memberships.email",
                            id(token, "membership_id"),
                            1,
                            c.verifiedEmail()),
                    tenant,
                    token.get("membership_id"));
        }
        db.exec(
                "update app.action_tokens set consumed_at=now() where tenant_id=? and id=?",
                tenant,
                token.get("id"));
        return object("id", tenant, "version", 1);
    }
}
