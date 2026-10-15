package com.psicocare;

import com.psicocare.shared.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.pattern.*;

import tools.jackson.databind.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@RestController
public class ApiController {
    record Route(JsonNode meta, PathPattern pattern) {}

    private final List<Route> routes = new ArrayList<>();
    private final List<ModuleHandler> handlers;
    private final Store db;
    private final Crypto crypto;
    private final TransactionTemplate tx;
    private final ContractValidator validator;
    private final ConcurrentMap<String, long[]> buckets = new ConcurrentHashMap<>();

    public ApiController(
            List<ModuleHandler> handlers,
            Store db,
            Crypto crypto,
            TransactionTemplate tx,
            ContractValidator validator)
            throws Exception {
        this.handlers = handlers;
        this.db = db;
        this.crypto = crypto;
        this.tx = tx;
        this.validator = validator;
        for (JsonNode n :
                Json.MAPPER.readTree(getClass().getResourceAsStream("/contracts/routes.json")))
            routes.add(new Route(n, new PathPatternParser().parse(n.path("path").asText())));
        routes.sort((a, b) -> PathPattern.SPECIFICITY_COMPARATOR.compare(a.pattern(), b.pattern()));
    }

    @RequestMapping(value = "/v1/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(
            HttpServletRequest request,
            Authentication authentication,
            @RequestBody(required = false) String raw) {
        Problem.require(
                raw == null || raw.getBytes(StandardCharsets.UTF_8).length <= 262144,
                413,
                "REQUEST_TOO_LARGE");
        var path = PathContainer.parsePath(request.getRequestURI());
        String method = request.getMethod();
        Route route =
                routes.stream()
                        .filter(
                                r ->
                                        r.meta().path("method").asText().equals(method)
                                                && r.pattern().matches(path))
                        .findFirst()
                        .orElseThrow(Problem::notFound);
        var meta = route.meta();
        var vars = route.pattern().matchAndExtract(path).getUriVariables();
        JsonNode body = raw == null || raw.isBlank() ? Json.object() : Json.parse(raw);
        validator.validate(meta.path("request").asText(), body);
        UUID idem = null;
        if (meta.path("idempotent").asBoolean()) {
            try {
                idem = UUID.fromString(request.getHeader("Idempotency-Key"));
            } catch (Exception e) {
                throw new Problem(400, "IDEMPOTENCY_KEY_REQUIRED");
            }
        }
        int expected = 0;
        if (meta.path("etag").asBoolean()) {
            String etag = request.getHeader("If-Match");
            Problem.require(etag != null, 428, "IF_MATCH_REQUIRED");
            Problem.require(etag.matches("\"v[1-9][0-9]*\""), 422, "INVALID_ETAG");
            expected = Integer.parseInt(etag.substring(2, etag.length() - 1));
        }
        String subject =
                authentication instanceof JwtAuthenticationToken j
                        ? j.getToken().getSubject()
                        : null;
        limit(subject == null ? request.getRemoteAddr() : subject, method.equals("GET") ? 300 : 60);
        Map<String, String> query = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> query.put(k, v[0]));
        UUID requestId = UUID.randomUUID(), key = idem;
        int version = expected;
        JsonNode result =
                tx.execute(
                        transaction -> {
                            UUID user =
                                    subject == null
                                            ? null
                                            : db.jdbc.queryForObject(
                                                    "select app.ensure_user(?)",
                                                    UUID.class,
                                                    subject);
                            UUID tenant =
                                    vars.containsKey("orgId") ? parseUuid(vars.get("orgId")) : null;
                            db.jdbc.queryForObject(
                                    "select set_config('app.tenant_id',?,true)",
                                    String.class,
                                    tenant == null ? "" : tenant.toString());
                            db.jdbc.queryForObject(
                                    "select set_config('app.actor_id',?,true)",
                                    String.class,
                                    user == null ? "" : user.toString());
                            Set<String> roles = new HashSet<>();
                            Instant mfa = null;
                            String email = null;
                            if (authentication instanceof JwtAuthenticationToken jwt) {
                                var amr = jwt.getToken().getClaimAsStringList("amr");
                                String acr = jwt.getToken().getClaimAsString("acr");
                                if ((amr != null && (amr.contains("otp") || amr.contains("mfa")))
                                        || "2".equals(acr))
                                    mfa = jwt.getToken().getClaimAsInstant("auth_time");
                                if (Boolean.TRUE.equals(
                                        jwt.getToken().getClaimAsBoolean("email_verified")))
                                    email = jwt.getToken().getClaimAsString("email");
                            }
                            if (tenant != null) {
                                if (user == null) throw new Problem(401, "UNAUTHENTICATED");
                                if (!db.exists(
                                        "select 1 from app.organizations where id=? and"
                                                + " status='ACTIVE'",
                                        tenant)) throw Problem.notFound();
                                db.optional(
                                                "select roles::text[] from"
                                                        + " app.organization_memberships where"
                                                        + " tenant_id=? and user_id=? and"
                                                        + " status='ACTIVE'",
                                                tenant,
                                                user)
                                        .ifPresent(
                                                r -> {
                                                    try {
                                                        roles.addAll(
                                                                Arrays.asList(
                                                                        (String[])
                                                                                ((java.sql.Array)
                                                                                                r
                                                                                                        .get(
                                                                                                                "roles"))
                                                                                        .getArray()));
                                                    } catch (Exception e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                });
                                boolean portal =
                                        db.exists(
                                                        "select 1 from app.patients where"
                                                            + " tenant_id=? and portal_user_id=?",
                                                        tenant,
                                                        user)
                                                || db.exists(
                                                        "select 1 from app.guardian_patient_access"
                                                            + " where tenant_id=? and"
                                                            + " guardian_user_id=? and"
                                                            + " legal_verified_at is not null and"
                                                            + " not review_required",
                                                        tenant,
                                                        user);
                                if (roles.isEmpty() && !portal) throw Problem.notFound();
                                if (!roles.isEmpty())
                                    Problem.require(mfa != null, 403, "STAFF_MFA_REQUIRED");
                                if (portal) roles.add("PATIENT");
                            }
                            Actor actor = new Actor(tenant, user, roles, mfa, requestId);
                            var command =
                                    new Command(
                                            method,
                                            meta.path("path").asText(),
                                            meta.path("tag").asText(),
                                            meta.path("permission").asText(),
                                            vars,
                                            query,
                                            body,
                                            actor,
                                            version,
                                            email,
                                            key);
                            authorizePolicy(command);
                            ModuleHandler handler =
                                    handlers.stream()
                                            .filter(h -> h.supports(command))
                                            .findFirst()
                                            .orElseThrow(Problem::notFound);
                            if (key == null || tenant == null) return handler.execute(command);
                            byte[] hash = Json.hash(Json.canonical(body));
                            db.jdbc.queryForObject(
                                    "select pg_advisory_xact_lock(hashtextextended(?,0))",
                                    Object.class,
                                    tenant
                                            + ":"
                                            + user
                                            + ":"
                                            + method
                                            + command.path()
                                            + ":"
                                            + key);
                            var previous =
                                    db.optional(
                                            "select * from app.http_idempotency where tenant_id=?"
                                                    + " and actor_user_id=? and route_key=? and"
                                                    + " idempotency_key=? and expires_at>now()",
                                            tenant,
                                            user,
                                            method + request.getRequestURI(),
                                            key);
                            if (previous.isPresent()) {
                                Problem.require(
                                        java.security.MessageDigest.isEqual(
                                                hash,
                                                (byte[]) previous.get().get("request_sha256")),
                                        409,
                                        "IDEMPOTENCY_MISMATCH");
                                return Json.parse(
                                        crypto.decrypt(
                                                tenant,
                                                "http_idempotency",
                                                key,
                                                1,
                                                (byte[]) previous.get().get("response_cipher")));
                            }
                            JsonNode response = handler.execute(command);
                            db.exec(
                                    "insert into"
                                        + " app.http_idempotency(tenant_id,actor_user_id,route_key,idempotency_key,request_sha256,result_status,response_cipher,expires_at)"
                                        + " values(?,?,?,?,?,?,?,now()+interval '72 hours') on"
                                        + " conflict(tenant_id,actor_user_id,route_key,idempotency_key)"
                                        + " do update set"
                                        + " request_sha256=excluded.request_sha256,result_status=excluded.result_status,response_cipher=excluded.response_cipher,expires_at=excluded.expires_at",
                                    tenant,
                                    user,
                                    method + request.getRequestURI(),
                                    key,
                                    hash,
                                    meta.path("status").asInt(),
                                    crypto.encrypt(
                                            tenant,
                                            "http_idempotency",
                                            key,
                                            1,
                                            response.toString()));
                            return response;
                        });
        var response =
                ResponseEntity.status(meta.path("status").asInt())
                        .cacheControl(CacheControl.noStore())
                        .header("X-Request-Id", requestId.toString());
        if (result != null && result.has("version"))
            response.eTag("\"v" + result.get("version").asInt() + "\"");
        return response.body(result);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw new Problem(422, "INVALID_UUID");
        }
    }

    private void authorizePolicy(Command c) {
        Actor a = c.actor();
        String p = c.permission();
        switch (p) {
            case "OWNER_OR_ADMIN",
                    "ASSIGNMENT_MANAGE",
                    "CONTENT_WRITE",
                    "CONTENT_PUBLISH",
                    "REPORT_MANAGE" ->
                    a.require("OWNER", "ADMIN");
            case "STAFF" -> a.require("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY", "BILLING");
            case "PATIENT_DIRECTORY", "PATIENT_WRITE", "APPOINTMENT_WRITE" ->
                    a.require("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY");
            case "BILLING_WRITE", "BILLING_PRIVILEGED", "BILLING_READ" ->
                    a.require("OWNER", "ADMIN", "BILLING");
            case "REPORT_READ" -> a.require("OWNER", "ADMIN", "SECRETARY", "BILLING");
            case "NOTE_AUTHOR_MFA" -> {
                a.require("PSYCHOLOGIST");
                a.recentMfa();
            }
            case "LOGIN", "USER_REGISTERED" ->
                    Problem.require(a.user() != null, 401, "UNAUTHENTICATED");
            default -> {} // Resource-specific predicates are enforced by the owning module and RLS.
        }
    }

    private void limit(String key, int max) {
        long now = System.currentTimeMillis() / 60000;
        long[] bucket =
                buckets.compute(
                        key,
                        (k, v) ->
                                v == null || v[0] != now
                                        ? new long[] {now, 1}
                                        : new long[] {now, v[1] + 1});
        if (buckets.size() > 10000) buckets.entrySet().removeIf(e -> e.getValue()[0] < now);
        Problem.require(bucket[1] <= max, 429, "RATE_LIMITED");
    }
}
