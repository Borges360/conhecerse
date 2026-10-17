package com.psicocare;

import static com.psicocare.shared.Json.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.psicocare.billing.BillingModule;
import com.psicocare.shared.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "app.environment=test",
            "app.encryption.provider=local",
            "app.encryption.local-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "app.encryption.lookup-key=BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=",
            "spring.flyway.enabled=false",
            "app.worker=false"
        })
@AutoConfigureMockMvc
class ApiIntegrationTest {
    static PostgreSQLContainer container;
    static String url, user, password;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) throws Exception {
        String external = System.getenv("TEST_PG_ADMIN_URL");
        if (external == null) {
            container = new PostgreSQLContainer("postgres:17.11-alpine");
            container.start();
            external = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } else {
            user = System.getenv().getOrDefault("TEST_PG_ADMIN_USER", "postgres");
            password = System.getenv().getOrDefault("TEST_PG_ADMIN_PASSWORD", "");
        }
        String adminUrl = external;
        String prefix = external.substring(0, external.lastIndexOf('/') + 1);
        url = prefix + "conhecerse_test";
        try (var conn = DriverManager.getConnection(adminUrl, user, password);
                var statement = conn.createStatement()) {
            for (String role :
                    List.of("conhecerse_migrator", "conhecerse_runtime", "conhecerse_web")) {
                try (var check = conn.prepareStatement("select 1 from pg_roles where rolname=?")) {
                    check.setString(1, role);
                    if (!check.executeQuery().next())
                        statement.execute(
                                "create role "
                                        + role
                                        + " login password 'test_only_password' nosuperuser"
                                        + " nocreatedb nocreaterole nobypassrls");
                }
            }
            try (var check =
                    statement.executeQuery(
                            "select 1 from pg_database where datname='conhecerse_test'")) {
                if (!check.next())
                    statement.execute("create database conhecerse_test owner conhecerse_migrator");
            }
        }
        String migratorPassword =
                System.getenv().getOrDefault("TEST_MIGRATOR_PASSWORD", "test_only_password");
        Flyway.configure()
                .dataSource(url, "conhecerse_migrator", migratorPassword)
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        props.add("spring.datasource.url", () -> url);
        props.add("spring.datasource.username", () -> "conhecerse_runtime");
        props.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("TEST_RUNTIME_PASSWORD", "test_only_password"));
        System.setProperty(
                "aws.accessKeyId",
                System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "synthetic-test"));
        System.setProperty(
                "aws.secretAccessKey",
                System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "synthetic-test"));
    }

    @Autowired MockMvc mvc;
    @Autowired Store db;
    @Autowired TransactionTemplate tx;
    @Autowired BillingModule billing;
    @Autowired Crypto crypto;
    @Autowired com.psicocare.reporting.ReportingModule reporting;
    UUID tenant, professional, patient, owner;
    String subject;

    @BeforeEach
    void fixture() throws Exception {
        subject = "test-" + UUID.randomUUID();
        var org =
                call(
                        post("/v1/orgs"),
                        object(
                                "displayName",
                                "Clínica sintética",
                                "slug",
                                "test-" + UUID.randomUUID().toString().substring(0, 8),
                                "legalPersonType",
                                "PF",
                                "ownerIsPsychologist",
                                true),
                        subject,
                        201);
        tenant = uuid(org, "id");
        owner = adminId("select id from app.auth_users where oidc_sub='" + subject + "'");
        professional = UUID.randomUUID();
        admin(
                "insert into"
                    + " app.professionals(tenant_id,id,user_id,display_name,crp_uf,crp_number,verification_status)"
                    + " values('"
                        + tenant
                        + "','"
                        + professional
                        + "','"
                        + owner
                        + "','Profissional sintético','RS','SYNTHETIC','VERIFIED')");
        for (int d = 1; d <= 7; d++)
            admin(
                    "insert into"
                        + " app.availability_rules(tenant_id,professional_id,weekday,local_start,local_end,effective_from,timezone)"
                        + " values('"
                            + tenant
                            + "','"
                            + professional
                            + "',"
                            + d
                            + ",'00:00','23:59','2020-01-01','America/Sao_Paulo')");
        var p =
                call(
                        post(base() + "/patients"),
                        object(
                                "fullName",
                                "CANARY_PERSON_SYNTHETIC",
                                "email",
                                "synthetic@example.invalid",
                                "professionalId",
                                professional),
                        subject,
                        201);
        patient = uuid(p, "id");
        admin(
                "update app.care_assignments set start_at=now()-interval '1 day' where tenant_id='"
                        + tenant
                        + "'");
    }

    private String base() {
        return "/v1/orgs/" + tenant;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r, String sub) {
        return r.with(
                        jwt().jwt(
                                        j ->
                                                j.subject(sub)
                                                        .claim("email_verified", true)
                                                        .claim("email", "owner@example.invalid")
                                                        .claim("amr", List.of("otp"))
                                                        .claim("auth_time", Instant.now())))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json");
    }

    private JsonNode call(MockHttpServletRequestBuilder r, JsonNode body, String sub, int status)
            throws Exception {
        var result =
                mvc.perform(auth(r, sub).content(body == null ? "" : body.toString())).andReturn();
        assertEquals(
                status,
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString().isBlank()
                ? object()
                : parse(result.getResponse().getContentAsString());
    }

    private JsonNode appointment(OffsetDateTime start) {
        return object(
                "patientId",
                patient,
                "professionalId",
                professional,
                "startAt",
                start.toString(),
                "endAt",
                start.plusMinutes(50).toString(),
                "modality",
                "REMOTE",
                "priceCents",
                18000);
    }

    private String staff(String role) throws Exception {
        String sub = "completion-" + UUID.randomUUID();
        UUID user = adminId("select app.ensure_user('" + sub + "')");
        admin(
                "insert into app.organization_memberships(tenant_id,user_id,roles) values('"
                        + tenant
                        + "','"
                        + user
                        + "',array['"
                        + role
                        + "']::app.member_role[])");
        return sub;
    }

    private void gate(String code) throws Exception {
        call(
                post(base() + "/release-approvals"),
                object("gateCode", code, "evidenceReference", "SYNTHETIC_APPROVAL_TEST_ONLY"),
                subject,
                201);
    }

    @Test
    void T041_emergencyRequiresTwoIndependentApprovalsAndExpires() throws Exception {
        gate("GATE-SECURITY-01");
        String requester = staff("ADMIN"), security = staff("ADMIN");
        var note =
                call(
                        post(base() + "/patients/" + patient + "/clinical-notes"),
                        object(
                                "patientId",
                                patient,
                                "occurredAt",
                                Instant.now().toString(),
                                "narrative",
                                "CANARY_EMERGENCY_SYNTHETIC"),
                        subject,
                        201);
        call(
                post(base() + "/clinical-notes/" + note.path("id").asText() + "/sign")
                        .header("If-Match", "\"v1\""),
                null,
                subject,
                201);
        var r =
                call(
                        post(base() + "/break-glass"),
                        object(
                                "patientId",
                                patient,
                                "reason",
                                "SYNTHETIC_EMERGENCY_TEST",
                                "scope",
                                "SIGNED_NOTES_READ",
                                "durationMinutes",
                                5),
                        requester,
                        201);
        String path = base() + "/break-glass/" + r.path("id").asText();
        call(get(path + "/notes"), null, requester, 404);
        call(
                post(path + "/approve"),
                object("reviewRole", "SECURITY", "evidence", "SYNTHETIC_REVIEW"),
                requester,
                403);
        call(
                post(path + "/approve"),
                object("reviewRole", "SECURITY", "evidence", "SYNTHETIC_REVIEW"),
                security,
                201);
        call(get(path + "/notes"), null, requester, 404);
        call(
                post(path + "/approve"),
                object("reviewRole", "CLINICAL", "evidence", "SYNTHETIC_REVIEW"),
                subject,
                201);
        assertEquals(
                "CANARY_EMERGENCY_SYNTHETIC",
                call(get(path + "/notes"), null, requester, 200)
                        .path("items")
                        .get(0)
                        .path("narrative")
                        .asText());
        call(get(base() + "/patients/" + patient + "/clinical-notes"), null, requester, 404);
        call(
                post(base() + "/patients/" + patient + "/clinical-notes"),
                object(
                        "patientId",
                        patient,
                        "occurredAt",
                        Instant.now().toString(),
                        "narrative",
                        "SHOULD_BE_BLOCKED"),
                requester,
                404);
        admin(
                "update app.break_glass_requests set expires_at=now()-interval '1 second' where"
                        + " tenant_id='"
                        + tenant
                        + "' and id='"
                        + r.path("id").asText()
                        + "'");
        call(get(path + "/notes"), null, requester, 404);
        call(post(path + "/revoke"), null, security, 201);
        call(get(path + "/notes"), null, requester, 404);
    }

    @Test
    void T042_registrationPatientMinimizationAndProfileReview() throws Exception {
        var address =
                object(
                        "street",
                        "RUA_SINTETICA",
                        "city",
                        "CIDADE_TESTE",
                        "state",
                        "RS",
                        "postalCode",
                        "00000000");
        var org =
                call(
                        patch(base()).header("If-Match", "\"v1\""),
                        object(
                                "registration",
                                object(
                                        "responsibleName",
                                        "RESPONSAVEL_SINTETICO",
                                        "responsibleTaxId",
                                        "00000000000",
                                        "address",
                                        address)),
                        subject,
                        200);
        assertEquals(
                "RESPONSAVEL_SINTETICO", org.path("registration").path("responsibleName").asText());
        var updated =
                call(
                        patch(base() + "/patients/" + patient).header("If-Match", "\"v1\""),
                        object(
                                "birthDate",
                                "2000-01-01",
                                "address",
                                address,
                                "emergencyContact",
                                object(
                                        "name",
                                        "CONTATO_SINTETICO",
                                        "phone",
                                        "+5511999999999",
                                        "clinicalIndication",
                                        "FINALIDADE_CLINICA_SINTETICA")),
                        subject,
                        200);
        assertEquals("RUA_SINTETICA", updated.path("address").path("street").asText());
        assertEquals("CONTATO_SINTETICO", updated.path("emergencyContact").path("name").asText());
        String admin = staff("ADMIN");
        assertFalse(
                call(get(base() + "/patients/" + patient), null, admin, 200)
                        .has("emergencyContact"));
        call(
                patch(base() + "/patients/" + patient).header("If-Match", "\"v2\""),
                object(
                        "emergencyContact",
                        object(
                                "name",
                                "BLOCKED",
                                "phone",
                                "+5511999999999",
                                "clinicalIndication",
                                "SYNTHETIC_REASON")),
                admin,
                403);
        var p =
                call(
                        patch(base() + "/professionals/" + professional)
                                .header("If-Match", "\"v1\""),
                        object(
                                "languages",
                                List.of("Português"),
                                "specialties",
                                List.of("SYNTHETIC_SPECIALTY"),
                                "appointmentTypes",
                                List.of("REMOTE"),
                                "publicProfileEnabled",
                                true),
                        subject,
                        200);
        assertEquals(0, p.path("reviewedSpecialties").size());
        call(
                post(base() + "/professionals/" + professional + "/review"),
                object(
                        "status",
                        "VERIFIED",
                        "evidence",
                        "SYNTHETIC_SPECIALTY_REVIEW",
                        "reviewedSpecialties",
                        List.of("UNDECLARED")),
                subject,
                422);
        call(
                post(base() + "/professionals/" + professional + "/review"),
                object(
                        "status",
                        "VERIFIED",
                        "evidence",
                        "SYNTHETIC_SPECIALTY_REVIEW",
                        "reviewedSpecialties",
                        List.of("SYNTHETIC_SPECIALTY")),
                subject,
                201);
    }

    @Test
    void T043_remoteProtocolIsEncryptedAndRequiresAssignedReview() throws Exception {
        var payload =
                object(
                        "interruptionPlan",
                        "CANARY_INTERRUPTION_SYNTHETIC",
                        "acuteRiskPlan",
                        "SYNTHETIC_RISK_PROTOCOL",
                        "privacyGuidance",
                        "SYNTHETIC_PRIVACY_GUIDANCE",
                        "privacyAcknowledged",
                        true);
        var r =
                call(
                        post(base() + "/patients/" + patient + "/remote-protocols"),
                        payload,
                        subject,
                        201);
        String path =
                base()
                        + "/patients/"
                        + patient
                        + "/remote-protocols/"
                        + r.path("id").asText()
                        + "/review";
        call(
                post(path),
                object("decision", "APPROVE", "evidence", "SYNTHETIC_PROTOCOL_REVIEW"),
                subject,
                422);
        gate("GATE-CLINICAL-01");
        call(
                post(path),
                object("decision", "APPROVE", "evidence", "SYNTHETIC_PROTOCOL_REVIEW"),
                staff("ADMIN"),
                404);
        call(
                post(path),
                object("decision", "APPROVE", "evidence", "SYNTHETIC_PROTOCOL_REVIEW"),
                subject,
                201);
        call(
                post(path),
                object("decision", "APPROVE", "evidence", "SYNTHETIC_PROTOCOL_REVIEW"),
                subject,
                409);
        assertEquals(
                "APPROVED",
                call(get(base() + "/patients/" + patient + "/remote-protocols"), null, subject, 200)
                        .path("items")
                        .get(0)
                        .path("status")
                        .asText());
    }

    @Test
    void T044_dailyLimitAndHolidayAreEnforced() throws Exception {
        call(
                patch(base() + "/professionals/" + professional).header("If-Match", "\"v1\""),
                object("appointmentTypes", List.of("REMOTE")),
                subject,
                200);
        call(
                patch(base() + "/settings").header("If-Match", "\"v1\""),
                object("maxAppointmentsPerDay", 1, "holidays", List.of("2027-01-01")),
                subject,
                200);
        var start =
                OffsetDateTime.now()
                        .plusDays(30)
                        .withHour(12)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        call(post(base() + "/appointments"), appointment(start), subject, 201);
        var unsupported = (tools.jackson.databind.node.ObjectNode) appointment(start.plusDays(1));
        unsupported.put("modality", "IN_PERSON");
        assertEquals(
                "MODALITY_NOT_OFFERED",
                call(post(base() + "/appointments"), unsupported, subject, 422)
                        .path("code")
                        .asText());
        var denied =
                call(post(base() + "/appointments"), appointment(start.plusHours(2)), subject, 409);
        assertEquals("DAILY_LIMIT_REACHED", denied.path("code").asText());
        call(
                post(base() + "/appointments"),
                appointment(OffsetDateTime.parse("2027-01-01T12:00:00-03:00")),
                subject,
                409);
        assertEquals(
                1,
                call(get(base() + "/settings/history"), null, subject, 200).path("items").size());
    }

    @Test
    void T045_metricsContainOnlyDailyAggregates() throws Exception {
        var content =
                call(
                        post(base() + "/content"),
                        object(
                                "entryType",
                                "BLOG",
                                "title",
                                "SYNTHETIC_PUBLIC_CONTENT",
                                "bodyHtml",
                                "<p>Reflexão pública sintética.</p>",
                                "visibility",
                                "PUBLIC",
                                "slug",
                                "synthetic-content"),
                        subject,
                        201);
        String id = content.path("id").asText();
        call(post(base() + "/content/" + id + "/actions/review"), null, subject, 201);
        call(post(base() + "/content/" + id + "/actions/publish"), null, subject, 201);
        var org = call(get(base()), null, subject, 200);
        String path = "/v1/public/blog/" + org.path("slug").asText() + "/" + id + "/read";
        call(post(path), object(), subject, 200);
        call(post(path), object(), subject, 200);
        var aggregate =
                call(get(base() + "/content-metrics"), null, subject, 200).path("items").get(0);
        assertEquals(2, aggregate.path("readCount").asInt());
        assertEquals(3, aggregate.size());
    }

    @Test
    void T046_guardianClinicalReleaseNeedsLegalAndClinicalReview() throws Exception {
        String guardian = "guardian-" + UUID.randomUUID();
        UUID user = adminId("select app.ensure_user('" + guardian + "')");
        var g =
                call(
                        post(base() + "/patients/" + patient + "/guardians"),
                        object(
                                "guardianUserId",
                                user,
                                "relationship",
                                "RESPONSAVEL_SINTETICO",
                                "accessScope",
                                List.of("SCHEDULE"),
                                "evidence",
                                "SYNTHETIC_LEGAL_EVIDENCE"),
                        subject,
                        201);
        gate("GATE-LEGAL-01");
        gate("GATE-CLINICAL-01");
        String legal = staff("ADMIN"),
                path = base() + "/patients/" + patient + "/guardians/" + g.path("id").asText();
        var review =
                object(
                        "evidence",
                        "SYNTHETIC_REVIEW_EVIDENCE",
                        "expiresAt",
                        Instant.now().plusSeconds(3600).toString());
        call(post(path + "/legal-review"), review, legal, 201);
        call(post(path + "/clinical-review"), review, legal, 404);
        call(post(path + "/clinical-review"), review, subject, 201);
        call(get(base() + "/patients/" + patient + "/clinical-notes"), null, guardian, 404);
        call(
                post(base() + "/patients/" + patient + "/privacy-requests"),
                object("type", "PORTABILITY"),
                guardian,
                201);
        call(post(path + "/revoke-clinical"), null, legal, 201);
        assertFalse(call(get(base() + "/portal/me"), null, guardian, 200).has("emergencyContact"));
    }

    @Test
    void T047_privacyCannotBeMarkedDeliveredWithoutAuthenticatedDownload() throws Exception {
        String portal = "portal-" + UUID.randomUUID();
        UUID user = adminId("select app.ensure_user('" + portal + "')");
        admin(
                "update app.patients set portal_user_id='"
                        + user
                        + "' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + patient
                        + "'");
        var r =
                call(
                        post(base() + "/patients/" + patient + "/privacy-requests"),
                        object("type", "ACCESS"),
                        portal,
                        201);
        call(
                post(base() + "/privacy-requests/" + r.path("id").asText() + "/review"),
                object("status", "FULFILLED", "decisionCode", "SYNTHETIC_UNDELIVERED"),
                subject,
                422);
        call(
                post(base() + "/privacy-requests/" + r.path("id").asText() + "/prepare-export"),
                object("noteIds", List.of(), "reviewedSummary", "SYNTHETIC_REVIEWED_SUMMARY"),
                portal,
                404);
        assertFalse(call(get(base()), null, portal, 200).has("registration"));
    }

    @Test
    void T048_platformBillingStaysSeparateAndUnconfiguredIsDenied() throws Exception {
        call(
                post(base() + "/platform-subscription"),
                object(
                        "planCode",
                        "UNAPPROVED",
                        "termsVersion",
                        "v1",
                        "customer",
                        object(
                                "name",
                                "SYNTHETIC_ORG",
                                "email",
                                "synthetic@example.invalid",
                                "cpfCnpj",
                                "00000000000")),
                subject,
                422);
        call(get(base() + "/platform-subscription/plans"), null, subject, 503);
        assertEquals(
                0,
                call(get(base() + "/platform-subscription"), null, subject, 200)
                        .path("items")
                        .size());
    }

    @Test
    void T001_crossTenantAndRevokedMembership() throws Exception {
        call(get("/v1/orgs/" + UUID.randomUUID() + "/patients/" + patient), null, subject, 404);
        admin(
                "update app.organization_memberships set status='REVOKED' where tenant_id='"
                        + tenant
                        + "' and user_id='"
                        + owner
                        + "'");
        call(get(base() + "/patients"), null, subject, 404);
    }

    @Test
    void T002_T003_clinicalAuthorizationAndCiphertext() throws Exception {
        var n =
                call(
                        post(base() + "/patients/" + patient + "/clinical-notes"),
                        object(
                                "patientId",
                                patient,
                                "occurredAt",
                                Instant.now().toString(),
                                "narrative",
                                "CANARY_CLINICAL_SYNTHETIC"),
                        subject,
                        201);
        UUID note = uuid(n, "id");
        String secretary = "secretary-" + UUID.randomUUID();
        UUID actor = register(secretary);
        admin(
                "insert into app.organization_memberships(tenant_id,user_id,roles) values('"
                        + tenant
                        + "','"
                        + actor
                        + "',array['SECRETARY']::app.member_role[])");
        call(get(base() + "/clinical-notes/" + note), null, secretary, 404);
        admin(
                "update app.organization_memberships set roles=array['OWNER']::app.member_role[]"
                        + " where tenant_id='"
                        + tenant
                        + "' and user_id='"
                        + owner
                        + "'");
        call(get(base() + "/clinical-notes/" + note), null, subject, 404);
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select convert_from(narrative_cipher,'UTF8') from"
                                        + " app.clinical_notes where id='"
                                        + note
                                        + "'")) {
            assertTrue(rs.next());
            assertFalse(rs.getString(1).contains("CANARY_CLINICAL"));
        }
    }

    @Test
    void T004_concurrentBookingUsesPostgresExclusion() throws Exception {
        var body =
                appointment(
                        OffsetDateTime.now()
                                .plusDays(1)
                                .withHour(12)
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () ->
                                        mvc.perform(
                                                        auth(
                                                                        post(
                                                                                base()
                                                                                        + "/appointments"),
                                                                        subject)
                                                                .content(body.toString()))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()));
            var statuses = new ArrayList<Integer>();
            for (var f : tasks) statuses.add(f.get());
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
        }
    }

    @Test
    void T005_rescheduleConflictRollsBackOriginal() throws Exception {
        var start =
                OffsetDateTime.now()
                        .plusDays(2)
                        .withHour(12)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        var original = call(post(base() + "/appointments"), appointment(start), subject, 201);
        UUID originalId = uuid(original, "id");
        call(post(base() + "/appointments/" + originalId + "/actions/accept"), null, subject, 201);
        call(post(base() + "/appointments"), appointment(start.plusHours(2)), subject, 201);
        var req =
                call(
                        post(base() + "/appointments/" + originalId + "/reschedule-requests"),
                        object(
                                "desiredStartAt",
                                start.plusHours(2).toString(),
                                "desiredEndAt",
                                start.plusHours(2).plusMinutes(50).toString()),
                        subject,
                        201);
        call(
                post(
                        base()
                                + "/reschedule-requests/"
                                + req.path("id").asText()
                                + "/actions/accept"),
                null,
                subject,
                409);
        assertEquals(
                "SCHEDULED",
                call(get(base() + "/appointments/" + originalId), null, subject, 200)
                        .path("status")
                        .asText());
    }

    @Test
    void T012_T013_signedNoteImmutableAndVersioned() throws Exception {
        var n =
                call(
                        post(base() + "/patients/" + patient + "/clinical-notes"),
                        object(
                                "patientId",
                                patient,
                                "occurredAt",
                                Instant.now().toString(),
                                "narrative",
                                "Registro sintético protegido"),
                        subject,
                        201);
        String id = n.path("id").asText();
        call(
                post(base() + "/clinical-notes/" + id + "/sign").header("If-Match", "\"v1\""),
                null,
                subject,
                201);
        var denied =
                call(
                        patch(base() + "/clinical-notes/" + id).header("If-Match", "\"v2\""),
                        object("narrative", "Mutação proibida"),
                        subject,
                        409);
        assertEquals("IMMUTABLE_RESOURCE", denied.path("code").asText());
        call(
                post(base() + "/clinical-notes/" + id + "/addenda"),
                object(
                        "patientId",
                        patient,
                        "occurredAt",
                        Instant.now().toString(),
                        "narrative",
                        "Adendo sintético"),
                subject,
                201);
    }

    @Test
    void T038_idempotencyMismatchAndReplay() throws Exception {
        String key = UUID.randomUUID().toString();
        var body =
                appointment(
                        OffsetDateTime.now()
                                .plusDays(1)
                                .withHour(15)
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0));
        var first =
                mvc.perform(
                                auth(post(base() + "/appointments"), subject)
                                        .header("Idempotency-Key", key)
                                        .content(body.toString()))
                        .andReturn();
        // header() appends, so replace explicitly for stable request identity.
        assertEquals(201, first.getResponse().getStatus());
        var builder =
                post(base() + "/appointments")
                        .with(
                                jwt().jwt(
                                                j ->
                                                        j.subject(subject)
                                                                .claim("amr", List.of("otp"))
                                                                .claim("auth_time", Instant.now())))
                        .contentType("application/json")
                        .header("Idempotency-Key", first.getRequest().getHeader("Idempotency-Key"));
        var replay = mvc.perform(builder.content(body.toString())).andReturn();
        assertEquals(
                first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        ((tools.jackson.databind.node.ObjectNode) body).put("priceCents", 19000);
        var mismatch = mvc.perform(builder.content(body.toString())).andReturn();
        assertEquals(409, mismatch.getResponse().getStatus());
        assertTrue(mismatch.getResponse().getContentAsString().contains("IDEMPOTENCY_MISMATCH"));
    }

    @Test
    void T039_missingAndStaleVersion() throws Exception {
        call(
                patch(base() + "/patients/" + patient),
                object("socialName", "Sintético"),
                subject,
                428);
        call(
                patch(base() + "/patients/" + patient).header("If-Match", "\"v99\""),
                object("socialName", "Sintético"),
                subject,
                412);
    }

    @Test
    void SEC001_runtimeHasNoBypassOrOwnershipAndFailsClosed() throws Exception {
        tx.execute(
                s -> {
                    assertTrue(db.rows("select * from app.patients").isEmpty());
                    assertTrue(db.rows("select * from app.clinical_notes").isEmpty());
                    return null;
                });
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select rolsuper,rolbypassrls from pg_roles where"
                                        + " rolname='conhecerse_runtime'")) {
            assertTrue(rs.next());
            assertFalse(rs.getBoolean(1));
            assertFalse(rs.getBoolean(2));
        }
    }

    @Test
    void T016_T017_T019_providerReplayAndCompensatingRefund() throws Exception {
        JsonNode payer =
                call(
                        post(base() + "/payers"),
                        object("fullName", "Pagador sintético", "type", "PF", "patientId", patient),
                        subject,
                        201);
        JsonNode invoice =
                call(
                        post(base() + "/invoices"),
                        object(
                                "payerId",
                                payer.path("id").asText(),
                                "dueOn",
                                LocalDate.now().toString(),
                                "items",
                                List.of(
                                        object(
                                                "descriptionCode",
                                                "ADJUSTMENT",
                                                "amountCents",
                                                18000))),
                        subject,
                        201);
        UUID iid = uuid(invoice, "id");
        call(post(base() + "/invoices/" + iid + "/actions/issue"), null, subject, 201);
        UUID payment = UUID.randomUUID();
        admin(
                "insert into"
                    + " app.payments(tenant_id,id,invoice_id,provider,provider_payment_ref,amount_cents,method)"
                    + " values('"
                        + tenant
                        + "','"
                        + payment
                        + "','"
                        + iid
                        + "','ASAAS','pay_synthetic',18000,'PIX')");
        JsonNode canonical =
                object(
                        "id",
                        "pay_synthetic",
                        "externalReference",
                        payment.toString(),
                        "value",
                        180,
                        "status",
                        "CONFIRMED");
        apply(payment, UUID.randomUUID(), canonical);
        assertEquals(
                "OPEN",
                call(get(base() + "/invoices/" + iid), null, subject, 200).path("status").asText());
        ((tools.jackson.databind.node.ObjectNode) canonical).put("status", "RECEIVED");
        UUID event = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int n = 0; n < 10; n++)
                futures.add(executor.submit(() -> apply(payment, event, canonical)));
            for (var future : futures) future.get();
        }
        assertEquals(
                "PAID",
                call(get(base() + "/invoices/" + iid), null, subject, 200).path("status").asText());
        ((tools.jackson.databind.node.ObjectNode) canonical).put("status", "PENDING");
        apply(payment, UUID.randomUUID(), canonical);
        assertEquals(
                "PAID",
                call(get(base() + "/invoices/" + iid), null, subject, 200).path("status").asText());
        ((tools.jackson.databind.node.ObjectNode) canonical).put("status", "REFUNDED");
        UUID refund = UUID.randomUUID();
        apply(payment, refund, canonical);
        apply(payment, refund, canonical);
        var state = call(get(base() + "/invoices/" + iid), null, subject, 200);
        assertEquals("REFUNDED", state.path("status").asText());
        assertEquals(0, state.path("paidCents").asLong());
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select count(*),sum(amount_cents) from app.ledger_entries where"
                                        + " invoice_id='"
                                        + iid
                                        + "'")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
            assertEquals(0, rs.getLong(2));
        }
    }

    @Test
    void T018_providerDisabledNeverMarksPaid() throws Exception {
        JsonNode payer =
                call(
                        post(base() + "/payers"),
                        object("fullName", "Pagador sintético", "type", "PF", "patientId", patient),
                        subject,
                        201);
        JsonNode invoice =
                call(
                        post(base() + "/invoices"),
                        object(
                                "payerId",
                                payer.path("id").asText(),
                                "dueOn",
                                LocalDate.now().toString(),
                                "items",
                                List.of(
                                        object(
                                                "descriptionCode",
                                                "ADJUSTMENT",
                                                "amountCents",
                                                18000))),
                        subject,
                        201);
        String iid = invoice.path("id").asText();
        call(post(base() + "/invoices/" + iid + "/actions/issue"), null, subject, 201);
        call(
                post(base() + "/invoices/" + iid + "/payments"),
                object("method", "PIX"),
                subject,
                503);
        assertEquals(
                0,
                call(get(base() + "/invoices/" + iid), null, subject, 200)
                        .path("paidCents")
                        .asLong());
    }

    private void apply(UUID payment, UUID event, JsonNode canonical) {
        tx.execute(
                status -> {
                    db.jdbc.queryForObject(
                            "select set_config('app.tenant_id',?,true)",
                            String.class,
                            tenant.toString());
                    db.jdbc.queryForObject(
                            "select set_config('app.actor_id',?,true)",
                            String.class,
                            owner.toString());
                    billing.applyCanonical(
                            new Actor(
                                    tenant,
                                    owner,
                                    Set.of("OWNER"),
                                    Instant.now(),
                                    UUID.randomUUID()),
                            payment,
                            event,
                            canonical);
                    return null;
                });
    }

    @Test
    void allContractOperationsRejectAnonymousAccess() throws Exception {
        JsonNode routes =
                Json.MAPPER.readTree(getClass().getResourceAsStream("/contracts/routes.json"));
        int count = 0;
        for (JsonNode route : routes) {
            if (route.path("public").asBoolean() || route.path("webhook").asBoolean()) continue;
            String path =
                    route.path("path")
                            .asText()
                            .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            var result =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.request(
                                            org.springframework.http.HttpMethod.valueOf(
                                                    route.path("method").asText()),
                                            path))
                            .andReturn();
            assertEquals(401, result.getResponse().getStatus(), path);
            count++;
        }
        assertTrue(count >= 80);
    }

    @Test
    void T006_confirmLinkIsIdempotent() throws Exception {
        var ap =
                call(
                        post(base() + "/appointments"),
                        appointment(
                                OffsetDateTime.now()
                                        .plusDays(3)
                                        .withHour(10)
                                        .withMinute(0)
                                        .withSecond(0)
                                        .withNano(0)),
                        subject,
                        201);
        UUID aid = uuid(ap, "id");
        call(post(base() + "/appointments/" + aid + "/actions/accept"), null, subject, 201);
        var nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        try (var conn = DriverManager.getConnection(url, user, password);
                var st =
                        conn.prepareStatement(
                                "insert into"
                                    + " app.action_tokens(tenant_id,token_hash,purpose,appointment_id,patient_id,expires_at)"
                                    + " values(?,?,'APPOINTMENT_CONFIRM',?,?,now()+interval '1"
                                    + " day')")) {
            st.setObject(1, tenant);
            st.setBytes(2, hash(token));
            st.setObject(3, aid);
            st.setObject(4, patient);
            st.execute();
        }
        for (int i = 0; i < 2; i++)
            assertEquals(
                    201,
                    mvc.perform(
                                    post("/v1/public/confirm")
                                            .contentType("application/json")
                                            .content(object("token", token).toString()))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        assertEquals(
                "CONFIRMED",
                call(get(base() + "/appointments/" + aid), null, subject, 200)
                        .path("status")
                        .asText());
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select count(*) from app.outbox_events where aggregate_id='"
                                        + aid
                                        + "' and event_type='AppointmentConfirmed'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void T023_newsletterConsentAndOptOut() throws Exception {
        String slug;
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select slug from app.organizations where id='" + tenant + "'")) {
            rs.next();
            slug = rs.getString(1);
        }
        var result =
                mvc.perform(
                                post("/v1/public/newsletter/subscribe")
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(
                                                object(
                                                                "orgSlug",
                                                                slug,
                                                                "email",
                                                                "newsletter@example.invalid",
                                                                "noticeVersion",
                                                                "synthetic-v1")
                                                        .toString()))
                        .andReturn();
        assertEquals(
                201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String token;
        UUID subscription;
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select id,subscription_id,delivery_cipher from app.action_tokens"
                                        + " where tenant_id='"
                                        + tenant
                                        + "' and purpose='NEWSLETTER_CONFIRM'")) {
            rs.next();
            subscription = rs.getObject(2, UUID.class);
            token =
                    parse(
                                    crypto.decrypt(
                                            tenant,
                                            "action_tokens",
                                            rs.getObject(1, UUID.class),
                                            1,
                                            rs.getBytes(3)))
                            .path("token")
                            .asText();
        }
        var confirm =
                mvc.perform(
                                post("/v1/public/newsletter/confirm")
                                        .contentType("application/json")
                                        .content(object("token", token).toString()))
                        .andReturn();
        assertEquals(
                201, confirm.getResponse().getStatus(), confirm.getResponse().getContentAsString());
        String unsub =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(UUID.randomUUID().toString().getBytes());
        try (var conn = DriverManager.getConnection(url, user, password);
                var st =
                        conn.prepareStatement(
                                "insert into"
                                    + " app.action_tokens(tenant_id,token_hash,purpose,subscription_id,consent_generation,expires_at)"
                                    + " select"
                                    + " tenant_id,?,'UNSUBSCRIBE',id,consent_generation,now()+interval"
                                    + " '1 day' from app.newsletter_subscriptions where id=?")) {
            st.setBytes(1, hash(unsub));
            st.setObject(2, subscription);
            st.execute();
        }
        for (int i = 0; i < 2; i++)
            assertEquals(
                    201,
                    mvc.perform(
                                    post("/v1/public/newsletter/unsubscribe")
                                            .contentType("application/json")
                                            .content(object("token", unsub).toString()))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        var denied =
                mvc.perform(
                                post("/v1/public/newsletter/confirm")
                                        .contentType("application/json")
                                        .content(object("token", token).toString()))
                        .andReturn();
        assertEquals(409, denied.getResponse().getStatus());
    }

    @Test
    void T024_concurrentDailyReportUnique() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 4; i++)
                tasks.add(
                        pool.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    db.jdbc.queryForObject(
                                                            "select"
                                                                + " set_config('app.tenant_id',?,true)",
                                                            String.class,
                                                            tenant.toString());
                                                    db.jdbc.queryForObject(
                                                            "select"
                                                                + " set_config('app.actor_id',?,true)",
                                                            String.class,
                                                            owner.toString());
                                                    return reporting.generate(
                                                            new Actor(
                                                                    tenant,
                                                                    owner,
                                                                    Set.of("OWNER"),
                                                                    Instant.now(),
                                                                    UUID.randomUUID()),
                                                            LocalDate.now(),
                                                            "America/Sao_Paulo");
                                                })));
            Set<String> ids = new HashSet<>();
            for (var task : tasks) ids.add(task.get().path("id").asText());
            assertEquals(1, ids.size());
        }
    }

    @Test
    void T039_appointmentPatchRequiresVersion() throws Exception {
        var ap =
                call(
                        post(base() + "/appointments"),
                        appointment(
                                OffsetDateTime.now()
                                        .plusDays(4)
                                        .withHour(10)
                                        .withMinute(0)
                                        .withSecond(0)
                                        .withNano(0)),
                        subject,
                        201);
        String aid = ap.path("id").asText();
        call(
                patch(base() + "/appointments/" + aid),
                object("reasonCode", "REVIEWED"),
                subject,
                428);
        var updated =
                call(
                        patch(base() + "/appointments/" + aid).header("If-Match", "\"v1\""),
                        object("reasonCode", "REVIEWED"),
                        subject,
                        200);
        assertEquals(2, updated.path("version").asInt());
        call(
                patch(base() + "/appointments/" + aid).header("If-Match", "\"v1\""),
                object("reasonCode", "STALE"),
                subject,
                412);
    }

    @Test
    void IAM_lastOwnerCannotBeRevoked() throws Exception {
        UUID membership =
                adminId(
                        "select id from app.organization_memberships where tenant_id='"
                                + tenant
                                + "' and user_id='"
                                + owner
                                + "'");
        call(
                patch(base() + "/members/" + membership).header("If-Match", "\"v1\""),
                object("status", "REVOKED", "roles", List.of("OWNER")),
                subject,
                409);
        String other = "staff-" + UUID.randomUUID();
        UUID uid = register(other);
        admin(
                "insert into app.organization_memberships(tenant_id,user_id,roles) values('"
                        + tenant
                        + "','"
                        + uid
                        + "',array['SECRETARY']::app.member_role[])");
        UUID mid =
                adminId(
                        "select id from app.organization_memberships where tenant_id='"
                                + tenant
                                + "' and user_id='"
                                + uid
                                + "'");
        call(
                patch(base() + "/members/" + mid).header("If-Match", "\"v1\""),
                object("status", "REVOKED", "roles", List.of("SECRETARY")),
                subject,
                200);
        call(get(base() + "/patients"), null, other, 404);
        assertEquals(0, call(get("/v1/me/organizations"), null, other, 200).path("items").size());
    }

    @Test
    void T009_videoDeniedBeforeAnyToken() throws Exception {
        var ap =
                call(
                        post(base() + "/appointments"),
                        appointment(
                                OffsetDateTime.now()
                                        .plusDays(1)
                                        .withHour(12)
                                        .withMinute(0)
                                        .withSecond(0)
                                        .withNano(0)),
                        subject,
                        201);
        call(
                post(base() + "/appointments/" + ap.path("id").asText() + "/video/access"),
                null,
                subject,
                503);
    }

    @Test
    void CLI004_restrictedFilesNeverJoinNormalRecord() throws Exception {
        JsonNode upload =
                call(
                        post(base() + "/files/uploads"),
                        object(
                                "filename",
                                "synthetic.pdf",
                                "contentType",
                                "application/pdf",
                                "sizeBytes",
                                100,
                                "sha256",
                                "0".repeat(64),
                                "classification",
                                "RESTRICTED_TEST",
                                "patientId",
                                patient),
                        subject,
                        201);
        String file = upload.path("id").asText();
        // Storage/AV are homologated separately; this fixture exercises the real metadata and
        // authorization boundaries.
        admin(
                "update app.file_assets set status='READY',malware_scan='CLEAN' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + file
                        + "'");
        call(
                post(base() + "/patients/" + patient + "/restricted-test-materials"),
                object("fileId", file),
                subject,
                201);
        call(
                post(base() + "/patients/" + patient + "/clinical-files"),
                object("fileId", file),
                subject,
                422);
        assertEquals(
                0,
                call(get(base() + "/patients/" + patient + "/clinical-files"), null, subject, 200)
                        .path("items")
                        .size());
        assertEquals(
                1,
                call(
                                get(base() + "/patients/" + patient + "/restricted-test-materials"),
                                null,
                                subject,
                                200)
                        .path("items")
                        .size());
        admin(
                "update app.organization_memberships set roles=array['OWNER']::app.member_role[]"
                        + " where tenant_id='"
                        + tenant
                        + "' and user_id='"
                        + owner
                        + "'");
        call(
                get(base() + "/patients/" + patient + "/restricted-test-materials"),
                null,
                subject,
                404);
        call(post(base() + "/files/" + file + "/download"), null, subject, 404);
    }

    @Test
    void T026_erasureNeverDeletesClinicalRecord() throws Exception {
        var req =
                call(
                        post(base() + "/patients/" + patient + "/privacy-requests"),
                        object("type", "ERASURE"),
                        subject,
                        201);
        call(
                post(base() + "/privacy-requests/" + req.path("id").asText() + "/review"),
                object("status", "FULFILLED", "decisionCode", "SYNTHETIC_DENIED_ERASURE"),
                subject,
                422);
        assertEquals(
                patient.toString(),
                call(get(base() + "/patients/" + patient), null, subject, 200).path("id").asText());
    }

    @Test
    void T040_portalLinksRemainIndependentAcrossOrganizations() throws Exception {
        admin(
                "update app.patients set portal_user_id='"
                        + owner
                        + "' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + patient
                        + "'");
        var org =
                call(
                        post("/v1/orgs"),
                        object(
                                "displayName",
                                "Outro espaço sintético",
                                "slug",
                                "portal-" + UUID.randomUUID().toString().substring(0, 8),
                                "legalPersonType",
                                "PF"),
                        subject,
                        201);
        String base2 = "/v1/orgs/" + org.path("id").asText();
        var p2 =
                call(
                        post(base2 + "/patients"),
                        object("fullName", "Outro cadastro sintético"),
                        subject,
                        201);
        admin(
                "update app.patients set portal_user_id='"
                        + owner
                        + "' where tenant_id='"
                        + org.path("id").asText()
                        + "' and id='"
                        + p2.path("id").asText()
                        + "'");
        assertEquals(
                patient.toString(),
                call(get(base() + "/portal/me"), null, subject, 200).path("id").asText());
        assertEquals(
                p2.path("id").asText(),
                call(get(base2 + "/portal/me"), null, subject, 200).path("id").asText());
        call(get(base2 + "/patients/" + patient), null, subject, 404);
    }

    @Test
    void SCH_publicLeadAcceptanceCreatesCareAndSessionAtomically() throws Exception {
        admin(
                "update app.professionals set public_profile_enabled=true where id='"
                        + professional
                        + "'");
        call(
                post(base() + "/professionals/" + professional + "/prices"),
                object(
                        "modality",
                        "REMOTE",
                        "amountCents",
                        18000,
                        "effectiveFrom",
                        LocalDate.now().toString()),
                subject,
                201);
        var start =
                OffsetDateTime.now()
                        .plusDays(3)
                        .withHour(14)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        var body =
                object(
                        "professionalId",
                        professional,
                        "patientName",
                        "Lead sintético",
                        "patientEmail",
                        "lead@example.invalid",
                        "startAt",
                        start.toString(),
                        "endAt",
                        start.plusMinutes(50).toString(),
                        "noticeVersion",
                        "synthetic-v1");
        String key = UUID.randomUUID().toString();
        var first =
                mvc.perform(
                                post("/v1/public/booking-requests")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body.toString()))
                        .andReturn();
        assertEquals(
                201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        var replay =
                mvc.perform(
                                post("/v1/public/booking-requests")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body.toString()))
                        .andReturn();
        assertEquals(
                first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        String id = parse(first.getResponse().getContentAsString()).path("id").asText();
        assertEquals(
                1,
                call(get(base() + "/booking-requests"), null, subject, 200).path("items").size());
        var accepted =
                call(
                        post(base() + "/booking-requests/" + id + "/actions/accept"),
                        null,
                        subject,
                        201);
        assertEquals("SCHEDULED", accepted.path("status").asText());
        assertNotEquals(patient.toString(), accepted.path("patientId").asText());
        assertEquals(
                1,
                call(
                                get(
                                        base()
                                                + "/patients/"
                                                + accepted.path("patientId").asText()
                                                + "/assignments"),
                                null,
                                subject,
                                200)
                        .path("items")
                        .size());
    }

    @Test
    void SCH_recurrenceIsBoundedAndIdempotent() throws Exception {
        var series =
                call(
                        post(base() + "/recurrence-series"),
                        object(
                                "patientId",
                                patient,
                                "professionalId",
                                professional,
                                "rrule",
                                "FREQ=WEEKLY;COUNT=2",
                                "localStartTime",
                                "09:00",
                                "timezone",
                                "America/Sao_Paulo",
                                "startsOn",
                                LocalDate.now().plusDays(1).toString(),
                                "maxCount",
                                2,
                                "durationMinutes",
                                50,
                                "modality",
                                "REMOTE",
                                "priceCents",
                                18000),
                        subject,
                        201);
        var appointments = call(get(base() + "/appointments?limit=1"), null, subject, 200);
        assertEquals(1, appointments.path("items").size());
        assertFalse(appointments.path("nextCursor").isNull());
        String cursor = appointments.path("nextCursor").asText();
        var second =
                call(get(base() + "/appointments?limit=1&cursor=" + cursor), null, subject, 200);
        assertEquals(1, second.path("items").size());
        assertNotEquals(
                appointments.path("items").get(0).path("id"),
                second.path("items").get(0).path("id"));
        assertTrue(second.path("nextCursor").isNull());
    }

    @Test
    void T007_lifecycleTerminalStatesArePersistedAndProtected() throws Exception {
        var ap =
                call(
                        post(base() + "/appointments"),
                        appointment(
                                OffsetDateTime.now()
                                        .plusDays(5)
                                        .withHour(12)
                                        .withMinute(0)
                                        .withSecond(0)
                                        .withNano(0)),
                        subject,
                        201);
        String id = ap.path("id").asText();
        admin(
                "update app.appointments set start_at=now()-interval '20"
                    + " minutes',end_at=now()+interval '30"
                    + " minutes',busy_range=tstzrange(now()-interval '20 minutes',now()+interval"
                    + " '30 minutes','[)') where id='"
                        + id
                        + "'");
        call(post(base() + "/appointments/" + id + "/actions/accept"), null, subject, 201);
        call(
                post(base() + "/appointments/" + id + "/actions/start"),
                object("reasonCode", "SYNTHETIC_TEST"),
                subject,
                201);
        assertEquals(
                "COMPLETED",
                call(
                                post(base() + "/appointments/" + id + "/actions/complete"),
                                object("reasonCode", "SYNTHETIC_TEST"),
                                subject,
                                201)
                        .path("status")
                        .asText());
        call(
                post(base() + "/appointments/" + id + "/actions/cancel"),
                object("reasonCode", "SYNTHETIC_TEST"),
                subject,
                409);
        call(post(base() + "/appointments/" + id + "/actions/start"), null, subject, 409);
    }

    @Test
    void BIL_financialFilterAggregatesAllPagesAndSeparatesPayer() throws Exception {
        var payer =
                call(
                        post(base() + "/payers"),
                        object(
                                "fullName",
                                "Responsável sintético",
                                "type",
                                "PF",
                                "patientId",
                                patient),
                        subject,
                        201);
        var i =
                call(
                        post(base() + "/invoices"),
                        object(
                                "payerId",
                                payer.path("id").asText(),
                                "dueOn",
                                LocalDate.now().toString(),
                                "items",
                                List.of(
                                        object(
                                                "descriptionCode",
                                                "ADJUSTMENT",
                                                "amountCents",
                                                8000))),
                        subject,
                        201);
        String id = i.path("id").asText();
        call(post(base() + "/invoices/" + id + "/actions/issue"), null, subject, 201);
        call(
                post(base() + "/invoices/" + id + "/manual-payments"),
                object("amountCents", 8000, "reason", "Conciliação sintética, sem dinheiro real."),
                subject,
                201);
        var report =
                call(
                        get(
                                base()
                                        + "/financial-reports?from="
                                        + LocalDate.now()
                                        + "&to="
                                        + LocalDate.now()
                                        + "&status=PAID&method=MANUAL"),
                        null,
                        subject,
                        200);
        assertEquals(8000, report.path("items").get(0).path("receivedCents").asInt());
        var receipt =
                call(get(base() + "/receipt-references"), null, subject, 200).path("items").get(0);
        assertEquals("RECEIPT_PENDING", receipt.path("status").asText());
        assertEquals("Responsável sintético", receipt.path("payerName").asText());
        assertEquals("CANARY_PERSON_SYNTHETIC", receipt.path("beneficiaryName").asText());
        call(
                post(base() + "/invoices/" + id + "/actions/cancel"),
                object("reasonCode", "SYNTHETIC_TEST"),
                subject,
                409);
    }

    @Test
    void T014_expiredDownloadTicketNeverReadsStorage() throws Exception {
        var file =
                call(
                        post(base() + "/files/uploads"),
                        object(
                                "filename",
                                "synthetic.pdf",
                                "contentType",
                                "application/pdf",
                                "sizeBytes",
                                100,
                                "sha256",
                                "0".repeat(64),
                                "classification",
                                "SENSITIVE_CLINICAL",
                                "patientId",
                                patient),
                        subject,
                        201);
        String token = UUID.randomUUID().toString();
        try (var conn = DriverManager.getConnection(url, user, password);
                var st =
                        conn.prepareStatement(
                                "insert into"
                                    + " app.download_tickets(tenant_id,file_id,actor_id,token_hash,expires_at)"
                                    + " values(?,?,?,?,now()-interval '1 second')")) {
            st.setObject(1, tenant);
            st.setObject(2, uuid(file, "id"));
            st.setObject(3, owner);
            st.setBytes(4, hash(token));
            st.execute();
        }
        call(
                get(base() + "/files/" + file.path("id").asText() + "/bytes?ticket=" + token),
                null,
                subject,
                403);
    }

    @Test
    void SCH_rescheduleSuccessAndDeclinePreserveHistory() throws Exception {
        var start =
                OffsetDateTime.now()
                        .plusDays(8)
                        .withHour(12)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        var original = call(post(base() + "/appointments"), appointment(start), subject, 201);
        String id = original.path("id").asText();
        call(post(base() + "/appointments/" + id + "/actions/accept"), null, subject, 201);
        var declined =
                call(
                        post(base() + "/appointments/" + id + "/reschedule-requests"),
                        object(
                                "desiredStartAt",
                                start.plusHours(2).toString(),
                                "desiredEndAt",
                                start.plusHours(2).plusMinutes(50).toString()),
                        subject,
                        201);
        call(
                post(
                        base()
                                + "/reschedule-requests/"
                                + declined.path("id").asText()
                                + "/actions/decline"),
                null,
                subject,
                201);
        assertEquals(
                "SCHEDULED",
                call(get(base() + "/appointments/" + id), null, subject, 200)
                        .path("status")
                        .asText());
        var request =
                call(
                        post(base() + "/appointments/" + id + "/reschedule-requests"),
                        object(
                                "desiredStartAt",
                                start.plusHours(3).toString(),
                                "desiredEndAt",
                                start.plusHours(3).plusMinutes(50).toString()),
                        subject,
                        201);
        var accepted =
                call(
                        post(
                                base()
                                        + "/reschedule-requests/"
                                        + request.path("id").asText()
                                        + "/actions/accept"),
                        null,
                        subject,
                        201);
        String newId = accepted.path("newAppointmentId").asText();
        assertEquals(
                "CANCELLED",
                call(get(base() + "/appointments/" + id), null, subject, 200)
                        .path("status")
                        .asText());
        assertEquals(
                id,
                call(get(base() + "/appointments/" + newId), null, subject, 200)
                        .path("rescheduledFromId")
                        .asText());
        assertTrue(
                call(get(base() + "/appointments/" + id + "/history"), null, subject, 200)
                                .path("items")
                                .size()
                        >= 3);
    }

    @Test
    void SCH_availabilityRulesAndAbsenceAreEnforced() throws Exception {
        call(
                post(base() + "/professionals/" + professional + "/availability"),
                object(
                        "weekday",
                        1,
                        "localStart",
                        "09:00",
                        "localEnd",
                        "17:00",
                        "effectiveFrom",
                        LocalDate.now().toString(),
                        "timezone",
                        "America/Sao_Paulo"),
                subject,
                201);
        assertEquals(
                8,
                call(
                                get(base() + "/professionals/" + professional + "/availability"),
                                null,
                                subject,
                                200)
                        .path("items")
                        .size());
        var start =
                OffsetDateTime.now()
                        .plusDays(9)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        call(
                post(base() + "/professionals/" + professional + "/exceptions"),
                object(
                        "fromAt",
                        start.toString(),
                        "toAt",
                        start.plusHours(1).toString(),
                        "reasonCode",
                        "SYNTHETIC_ABSENCE"),
                subject,
                201);
        call(post(base() + "/appointments"), appointment(start), subject, 409);
        var ap =
                call(post(base() + "/appointments"), appointment(start.plusHours(3)), subject, 201);
        call(
                post(base() + "/professionals/" + professional + "/exceptions"),
                object(
                        "fromAt",
                        start.plusHours(3).toString(),
                        "toAt",
                        start.plusHours(4).toString(),
                        "reasonCode",
                        "SYNTHETIC_CONFLICT"),
                subject,
                409);
    }

    @Test
    void CLI_authorCanEditAndReadOnlyOwnDraft() throws Exception {
        var note =
                call(
                        post(base() + "/patients/" + patient + "/clinical-notes"),
                        object(
                                "patientId",
                                patient,
                                "occurredAt",
                                Instant.now().toString(),
                                "narrative",
                                "Rascunho inicial"),
                        subject,
                        201);
        String id = note.path("id").asText();
        call(
                patch(base() + "/clinical-notes/" + id).header("If-Match", "\"v1\""),
                object(
                        "narrative",
                        "Rascunho revisado",
                        "plan",
                        "Plano sintético",
                        "referrals",
                        "Nenhum encaminhamento"),
                subject,
                200);
        var read = call(get(base() + "/clinical-notes/" + id), null, subject, 200);
        assertEquals("Rascunho revisado", read.path("narrative").asText());
        assertEquals(2, read.path("version").asInt());
        assertEquals(
                1,
                call(get(base() + "/patients/" + patient + "/clinical-notes"), null, subject, 200)
                        .path("items")
                        .size());
    }

    @Test
    void PAT_guardianRequiresVerifiedAndCurrentScope() throws Exception {
        String guardian = "guardian-" + UUID.randomUUID();
        UUID uid = register(guardian);
        var grant =
                call(
                        post(base() + "/patients/" + patient + "/guardians"),
                        object(
                                "guardianUserId",
                                uid,
                                "relationship",
                                "SYNTHETIC_LEGAL_GUARDIAN",
                                "accessScope",
                                List.of("SCHEDULE"),
                                "evidence",
                                "SYNTHETIC_REVIEW_ONLY"),
                        subject,
                        201);
        call(get(base() + "/patients/" + patient), null, guardian, 200);
        call(
                patch(base() + "/patients/" + patient).header("If-Match", "\"v1\""),
                object("socialName", "Not permitted"),
                guardian,
                404);
        call(get(base() + "/patients/" + patient + "/clinical-notes"), null, guardian, 404);
        admin(
                "update app.guardian_patient_access set review_required=true where id='"
                        + grant.path("id").asText()
                        + "'");
        call(get(base() + "/patients/" + patient), null, guardian, 404);
    }

    @Test
    void PAT_guardianCanReadInvoicesOnlyWithCurrentBillingScope() throws Exception {
        String guardian = "guardian-billing-" + UUID.randomUUID();
        UUID uid = register(guardian);
        call(
                post(base() + "/patients/" + patient + "/guardians"),
                object(
                        "guardianUserId",
                        uid,
                        "relationship",
                        "SYNTHETIC_BILLING_GUARDIAN",
                        "accessScope",
                        List.of("SCHEDULE", "BILLING"),
                        "evidence",
                        "SYNTHETIC_BILLING_SCOPE_ONLY"),
                subject,
                201);
        var payer =
                call(
                        post(base() + "/payers"),
                        object(
                                "fullName",
                                "Pagador sintético",
                                "type",
                                "PF",
                                "patientId",
                                patient),
                        subject,
                        201);
        var invoice =
                call(
                        post(base() + "/invoices"),
                        object(
                                "payerId",
                                payer.path("id").asText(),
                                "dueOn",
                                LocalDate.now().plusDays(1).toString(),
                                "items",
                                List.of(
                                        object(
                                                "descriptionCode",
                                                "SYNTHETIC_SESSION",
                                                "amountCents",
                                                1000))),
                        subject,
                        201);
        String invoicePath = base() + "/invoices/" + invoice.path("id").asText();
        assertEquals(
                invoice.path("id").asText(),
                call(get(base() + "/portal/invoices"), null, guardian, 200)
                        .path("items")
                        .get(0)
                        .path("id")
                        .asText());
        call(get(invoicePath), null, guardian, 200);
        admin(
                "update app.guardian_patient_access set access_scope=ARRAY['SCHEDULE'] where"
                        + " guardian_user_id='"
                        + uid
                        + "' and tenant_id='"
                        + tenant
                        + "'");
        assertEquals(
                0,
                call(get(base() + "/portal/invoices"), null, guardian, 200)
                        .path("items")
                        .size());
        call(get(invoicePath), null, guardian, 404);
    }

    @Test
    void PAT_portalCanReadOwnInvoiceAndWithdrawMarketing() throws Exception {
        String patientSub = "portal-" + UUID.randomUUID();
        UUID uid = register(patientSub);
        admin("update app.patients set portal_user_id='" + uid + "' where id='" + patient + "'");
        var payer =
                call(
                        post(base() + "/payers"),
                        object(
                                "fullName",
                                "Responsável sintético",
                                "type",
                                "PF",
                                "patientId",
                                patient),
                        subject,
                        201);
        var invoice =
                call(
                        post(base() + "/invoices"),
                        object(
                                "payerId",
                                payer.path("id").asText(),
                                "dueOn",
                                LocalDate.now().toString(),
                                "items",
                                List.of(
                                        object(
                                                "descriptionCode",
                                                "ADJUSTMENT",
                                                "amountCents",
                                                18000))),
                        subject,
                        201);
        call(get(base() + "/invoices/" + invoice.path("id").asText()), null, patientSub, 200);
        call(get(base() + "/portal/invoices"), null, patientSub, 200);
        call(
                patch(base() + "/patients/" + patient).header("If-Match", "\"v1\""),
                object("fullName", "Forbidden legal rename"),
                patientSub,
                403);
        call(
                patch(base() + "/portal/preferences").header("If-Match", "\"v1\""),
                object("marketingEmail", true, "noticeVersion", "synthetic-v1"),
                patientSub,
                200);
        assertTrue(
                call(get(base() + "/portal/preferences"), null, patientSub, 200)
                        .path("marketingEmail")
                        .asBoolean());
        call(
                patch(base() + "/portal/preferences").header("If-Match", "\"v2\""),
                object("marketingEmail", false, "noticeVersion", "synthetic-v1"),
                patientSub,
                200);
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                "select count(*) from app.newsletter_subscriptions where"
                                        + " tenant_id='"
                                        + tenant
                                        + "' and unsubscribed_at is not null")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void BIL_checkoutIsOnlyAnIntentAndCancellationRequiresNoPendingPayment() throws Exception {
        var connection =
                call(
                        post(base() + "/integrations"),
                        object(
                                "provider",
                                "ASAAS",
                                "label",
                                "SYNTHETIC_OFFLINE_ADAPTER",
                                "secretsPath",
                                "conhecerse/dev/synthetic",
                                "capabilities",
                                object("environment", "sandbox")),
                        subject,
                        201);
        admin(
                "update app.integration_connections set status='ACTIVE' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + connection.path("id").asText()
                        + "'");
        call(
                post(base() + "/release-approvals"),
                object(
                        "gateCode",
                        "GATE-PSP-01",
                        "evidenceReference",
                        "SYNTHETIC_FIXTURE_NOT_A_REAL_APPROVAL"),
                subject,
                201);
        var payer =
                call(
                        post(base() + "/payers"),
                        object("fullName", "Pagador sintético", "type", "PF", "patientId", patient),
                        subject,
                        201);
        call(get(base() + "/payers"), null, subject, 200);
        var body =
                object(
                        "payerId",
                        payer.path("id").asText(),
                        "dueOn",
                        LocalDate.now().toString(),
                        "items",
                        List.of(object("descriptionCode", "ADJUSTMENT", "amountCents", 18000)));
        var i = call(post(base() + "/invoices"), body, subject, 201);
        String id = i.path("id").asText();
        call(post(base() + "/invoices/" + id + "/actions/issue"), null, subject, 201);
        var checkout =
                call(
                        post(base() + "/invoices/" + id + "/payments"),
                        object("method", "PIX"),
                        subject,
                        201);
        assertEquals("PENDING", checkout.path("status").asText());
        assertEquals(
                "PENDING",
                call(
                                get(base() + "/payments/" + checkout.path("paymentId").asText()),
                                null,
                                subject,
                                200)
                        .path("status")
                        .asText());
        call(post(base() + "/invoices/" + id + "/payments"), object("method", "PIX"), subject, 409);
        call(
                post(base() + "/invoices/" + id + "/actions/cancel"),
                object("reasonCode", "SYNTHETIC_TEST"),
                subject,
                409);
        assertEquals(
                "OPEN",
                call(get(base() + "/invoices/" + id), null, subject, 200).path("status").asText());
        var draft = call(post(base() + "/invoices"), body, subject, 201);
        assertEquals(
                "CANCELLED",
                call(
                                post(
                                        base()
                                                + "/invoices/"
                                                + draft.path("id").asText()
                                                + "/actions/cancel"),
                                object("reasonCode", "SYNTHETIC_TEST"),
                                subject,
                                201)
                        .path("status")
                        .asText());
    }

    private UUID register(String sub) throws Exception {
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.prepareStatement("select app.ensure_user(?)")) {
            st.setString(1, sub);
            var rs = st.executeQuery();
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private UUID adminId(String sql) throws Exception {
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private void admin(String sql) throws Exception {
        try (var conn = DriverManager.getConnection(url, user, password);
                var st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
