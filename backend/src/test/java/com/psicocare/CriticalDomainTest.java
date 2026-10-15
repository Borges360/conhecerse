package com.psicocare;

import static com.psicocare.shared.Json.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.psicocare.billing.BillingModule;
import com.psicocare.clinical.ClinicalModule;
import com.psicocare.scheduling.SchedulingModule;
import com.psicocare.shared.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

/** Exercises domain defenses independently of controller checks and PostgreSQL RLS. */
class CriticalDomainTest {
    UUID tenant = UUID.randomUUID(),
            user = UUID.randomUUID(),
            patient = UUID.randomUUID(),
            pro = UUID.randomUUID(),
            id = UUID.randomUUID();
    Actor owner =
            new Actor(
                    tenant,
                    user,
                    Set.of("OWNER", "PSYCHOLOGIST"),
                    Instant.now(),
                    UUID.randomUUID());
    Store db;
    JdbcTemplate jdbc;
    Access access;
    Events events;
    Crypto crypto;
    ClinicalModule clinical;
    BillingModule billing;
    SchedulingModule scheduling;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        db = mock(Store.class);
        ReflectionTestUtils.setField(db, "jdbc", jdbc);
        access = mock(Access.class);
        events = mock(Events.class);
        crypto = mock(Crypto.class);
        when(crypto.decrypt(any(), anyString(), any(), anyInt(), any()))
                .thenReturn("Texto sintético");
        when(crypto.encrypt(any(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(new byte[] {1});
        clinical = new ClinicalModule(db, access, crypto, events);
        billing = new BillingModule(db, crypto, access, events);
        scheduling =
                new SchedulingModule(db, access, events, crypto, mock(PatientEnrollment.class));
    }

    Command command(
            String method,
            String path,
            JsonNode body,
            Actor actor,
            Map<String, String> vars,
            int version) {
        return new Command(
                method,
                path,
                "",
                "",
                vars,
                Map.of(),
                body,
                actor,
                version,
                null,
                UUID.randomUUID());
    }

    Command c(String method, String path, JsonNode body, Map<String, String> vars) {
        return command(method, path, body, owner, vars, 1);
    }

    Map<String, Object> note() {
        var r = new HashMap<String, Object>();
        r.put("id", id);
        r.put("patient_id", patient);
        r.put("author_user_id", user);
        r.put("status", "DRAFT");
        r.put("version", 1);
        r.put("occurred_at", Instant.now());
        r.put("note_type", "EVOLUTION");
        r.put("narrative_cipher", new byte[] {1});
        r.put("plan_cipher", new byte[] {1});
        r.put("referrals_cipher", new byte[] {1});
        return r;
    }

    Map<String, Object> invoice(String status, long total, long paid) {
        return new HashMap<>(
                Map.of(
                        "id",
                        id,
                        "payer_id",
                        UUID.randomUUID(),
                        "status",
                        status,
                        "amount_cents",
                        total,
                        "paid_cents",
                        paid,
                        "version",
                        1,
                        "due_on",
                        LocalDate.now()));
    }

    void row(Map<String, Object> row) {
        when(db.one(anyString(), any(Object[].class))).thenReturn(row);
    }

    void problem(String code, Runnable action) {
        assertEquals(code, assertThrows(Problem.class, action::run).code);
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 180})
    void durationIncludesBothBounds(int minutes) {
        var start = OffsetDateTime.now();
        assertDoesNotThrow(() -> SchedulingModule.duration(start, start.plusMinutes(minutes)));
    }

    @Test
    void clinicalCreateRejectsWrongPatientAndFutureTime() {
        var vars = Map.of("patientId", patient.toString());
        problem(
                "PATIENT_MISMATCH",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/patients/x/clinical-notes",
                                        object("patientId", UUID.randomUUID()),
                                        vars)));
        problem(
                "INVALID_LOCAL_TIME",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/patients/x/clinical-notes",
                                        object(
                                                "patientId",
                                                patient,
                                                "occurredAt",
                                                OffsetDateTime.now().plusHours(1).toString(),
                                                "narrative",
                                                "Sintético"),
                                        vars)));
        problem(
                "USE_ADDENDUM_ENDPOINT",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/patients/x/clinical-notes",
                                        object("patientId", patient, "revisionOfId", id),
                                        vars)));
    }

    @Test
    void clinicalAppointmentMustBelongToPatientAndProfessional() {
        problem(
                "RESOURCE_NOT_FOUND",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/patients/x/clinical-notes",
                                        object(
                                                "patientId",
                                                patient,
                                                "appointmentId",
                                                id,
                                                "occurredAt",
                                                Instant.now().toString()),
                                        Map.of("patientId", patient.toString()))));
    }

    @Test
    void clinicalDraftCreationChecksAccessAndAudits() {
        row(note());
        var result =
                clinical.execute(
                        c(
                                "POST",
                                "/patients/x/clinical-notes",
                                object(
                                        "patientId",
                                        patient,
                                        "occurredAt",
                                        Instant.now().toString(),
                                        "narrative",
                                        "Sintético"),
                                Map.of("patientId", patient.toString())));
        assertEquals("DRAFT", result.path("status").asText());
        verify(access, times(2)).requireClinical(owner, patient);
        verify(events).audit(eq(owner), eq("ClinicalDraftCreated"), eq("clinicalNote"), any());
    }

    @Test
    void clinicalListContainsNarrativeAndMetadata() {
        when(db.rows(anyString(), any(Object[].class))).thenReturn(List.of(note()));
        var n =
                clinical.execute(
                                c(
                                        "GET",
                                        "/patients/x/clinical-notes",
                                        object(),
                                        Map.of("patientId", patient.toString())))
                        .path("items")
                        .get(0);
        assertEquals(id.toString(), n.path("id").asText());
        assertEquals("Texto sintético", n.path("narrative").asText());
    }

    @Test
    void clinicalDraftWriteRequiresAuthorVersionAndAccess() {
        var r = note();
        row(r);
        var vars = Map.of("noteId", id.toString());
        problem(
                "STALE_VERSION",
                () ->
                        clinical.execute(
                                command(
                                        "PATCH",
                                        "/clinical-notes/x",
                                        object("plan", "Plano"),
                                        owner,
                                        vars,
                                        9)));
        r.put("author_user_id", UUID.randomUUID());
        problem(
                "RESOURCE_NOT_FOUND",
                () -> clinical.execute(c("PATCH", "/clinical-notes/x", object(), vars)));
        r.put("author_user_id", user);
        clinical.execute(c("PATCH", "/clinical-notes/x", object("plan", "Plano"), vars));
        verify(access, times(3)).requireClinical(owner, patient);
        verify(events).audit(owner, "ClinicalDraftSaved", "clinicalNote", id);
    }

    @Test
    void clinicalSignatureRequiresRecentMfaAndNonemptyText() {
        row(note());
        var vars = Map.of("noteId", id.toString());
        var stale =
                new Actor(
                        tenant,
                        user,
                        owner.roles(),
                        Instant.now().minusSeconds(1800),
                        UUID.randomUUID());
        problem(
                "RECENT_MFA_REQUIRED",
                () ->
                        clinical.execute(
                                command(
                                        "POST",
                                        "/clinical-notes/x/sign",
                                        object(),
                                        stale,
                                        vars,
                                        1)));
        when(crypto.decrypt(any(), anyString(), any(), anyInt(), any())).thenReturn("");
        problem(
                "REQUIRED_FIELD_MISSING",
                () -> clinical.execute(c("POST", "/clinical-notes/x/sign", object(), vars)));
    }

    @Test
    void clinicalSignatureProducesEventAndHash() {
        row(note());
        clinical.execute(
                c("POST", "/clinical-notes/x/sign", object(), Map.of("noteId", id.toString())));
        verify(events).emit(owner, "ClinicalNoteSigned", "clinicalNote", id, 2);
        verify(db).exec(contains("content_sha256"), any(Object[].class));
    }

    @Test
    void addendumRequiresSignedSamePatient() {
        var r = note();
        row(r);
        var vars = Map.of("noteId", id.toString());
        problem(
                "INVALID_TRANSITION",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/clinical-notes/x/addenda",
                                        object("patientId", patient),
                                        vars)));
        r.put("status", "SIGNED");
        problem(
                "PATIENT_MISMATCH",
                () ->
                        clinical.execute(
                                c(
                                        "POST",
                                        "/clinical-notes/x/addenda",
                                        object("patientId", UUID.randomUUID()),
                                        vars)));
    }

    @Test
    void appointmentPatchAndRescheduleEnforceStateDurationAndAudit() {
        var r =
                new HashMap<String, Object>(
                        Map.of(
                                "id",
                                id,
                                "patient_id",
                                patient,
                                "status",
                                "SCHEDULED",
                                "modality",
                                "REMOTE",
                                "version",
                                1));
        row(r);
        var vars = Map.of("appointmentId", id.toString());
        scheduling.execute(c("PATCH", "/appointments/x", object("reasonCode", "SYNTHETIC"), vars));
        verify(access).appointment(owner, r, true);
        verify(access).appointment(owner, r, false);
        verify(events).audit(owner, "AppointmentDetailsUpdated", "appointment", id);
        r.put("status", "COMPLETED");
        problem(
                "IMMUTABLE_RESOURCE",
                () -> scheduling.execute(c("PATCH", "/appointments/x", object(), vars)));
        var start = OffsetDateTime.now().plusDays(1);
        problem(
                "INVALID_TRANSITION",
                () ->
                        scheduling.execute(
                                c(
                                        "POST",
                                        "/appointments/x/reschedule-requests",
                                        object(
                                                "desiredStartAt",
                                                start.toString(),
                                                "desiredEndAt",
                                                start.plusMinutes(50).toString()),
                                        vars)));
        r.put("status", "SCHEDULED");
        problem(
                "INVALID_DURATION",
                () ->
                        scheduling.execute(
                                c(
                                        "POST",
                                        "/appointments/x/reschedule-requests",
                                        object(
                                                "desiredStartAt",
                                                start.toString(),
                                                "desiredEndAt",
                                                start.plusMinutes(20).toString()),
                                        vars)));
        var result =
                scheduling.execute(
                        c(
                                "POST",
                                "/appointments/x/reschedule-requests",
                                object(
                                        "desiredStartAt",
                                        start.toString(),
                                        "desiredEndAt",
                                        start.plusMinutes(50).toString()),
                                vars));
        assertEquals(id.toString(), result.path("id").asText());
        verify(events).audit(eq(owner), eq("RescheduleRequested"), eq("rescheduleRequest"), any());
    }

    @Test
    void confirmationTokenReturnsVersionAndAuditedHistoryAndRejectsLateAction() {
        var route = Map.<String, Object>of("tenant_id", tenant);
        var token =
                new HashMap<String, Object>(Map.of("id", UUID.randomUUID(), "appointment_id", id));
        var ap =
                new HashMap<String, Object>(
                        Map.of(
                                "id",
                                id,
                                "start_at",
                                Instant.now().plusSeconds(3600),
                                "status",
                                "SCHEDULED",
                                "version",
                                3));
        when(db.one(contains("app.action_routes"), any(Object[].class))).thenReturn(route);
        when(db.one(contains("app.action_tokens"), any(Object[].class))).thenReturn(token);
        when(db.one(contains("app.appointments"), any(Object[].class))).thenReturn(ap);
        var c =
                c(
                        "POST",
                        "/v1/public/confirm",
                        object("token", "synthetic-action-token"),
                        Map.of());
        var result = scheduling.execute(c);
        assertEquals(4, result.path("version").asInt());
        assertEquals(id.toString(), result.path("id").asText());
        verify(events).emit(any(), eq("AppointmentConfirmed"), eq("appointment"), eq(id), eq(4));
        verify(db).exec(contains("app.appointment_history"), any(Object[].class));
        ap.put("start_at", Instant.now().minusSeconds(10));
        problem("INVALID_TRANSITION", () -> scheduling.execute(c));
        token.put("consumed_at", Instant.now());
        assertEquals(id.toString(), scheduling.execute(c).path("id").asText());
    }

    Actor outsider() {
        return new Actor(
                tenant, UUID.randomUUID(), Set.of("PATIENT"), Instant.now(), UUID.randomUUID());
    }

    @Test
    void pricingDomainRequiresOwnerAndConsistentDates() {
        var vars = Map.of("professionalId", pro.toString());
        var b =
                object(
                        "modality",
                        "REMOTE",
                        "amountCents",
                        100,
                        "effectiveFrom",
                        "2026-10-10",
                        "effectiveUntil",
                        "2026-10-09");
        problem(
                "PERMISSION_DENIED",
                () ->
                        billing.execute(
                                command(
                                        "POST",
                                        "/professionals/x/prices",
                                        b,
                                        outsider(),
                                        vars,
                                        1)));
        problem(
                "INVALID_PRICE_PERIOD",
                () -> billing.execute(c("POST", "/professionals/x/prices", b, vars)));
        var valid =
                object(
                        "modality",
                        "REMOTE",
                        "amountCents",
                        100,
                        "effectiveFrom",
                        "2026-10-10",
                        "effectiveUntil",
                        "2026-10-10");
        when(db.exists(anyString(), any(Object[].class))).thenReturn(true);
        problem(
                "PRICE_PERIOD_OVERLAP",
                () -> billing.execute(c("POST", "/professionals/x/prices", valid, vars)));
        when(db.exists(anyString(), any(Object[].class))).thenReturn(false);
        var result = billing.execute(c("POST", "/professionals/x/prices", valid, vars));
        assertEquals(1, result.path("version").asInt());
        verify(events).audit(eq(owner), eq("PriceRuleCreated"), eq("priceRule"), any());
    }

    @Test
    void priceAndPayerListsExposeOnlyTheirContract() {
        when(db.rows(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        id,
                                        "modality",
                                        "REMOTE",
                                        "amount_cents",
                                        25000,
                                        "effective_from",
                                        LocalDate.now(),
                                        "status",
                                        "ACTIVE")));
        var prices =
                billing.execute(
                                c(
                                        "GET",
                                        "/professionals/x/prices",
                                        object(),
                                        Map.of("professionalId", pro.toString())))
                        .path("items");
        assertEquals(25000, prices.get(0).path("amountCents").asInt());
        when(db.rows(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", id, "name_cipher", new byte[] {1})));
        var payers = billing.execute(c("GET", "/payers", object(), Map.of())).path("items");
        assertEquals("Texto sintético", payers.get(0).path("displayName").asText());
        problem(
                "PERMISSION_DENIED",
                () ->
                        billing.execute(
                                command("GET", "/payers", object(), outsider(), Map.of(), 1)));
    }

    @Test
    void payerCreationIsAuthorizedAndAudited() {
        var result =
                billing.execute(
                        c(
                                "POST",
                                "/payers",
                                object("fullName", "Sintético", "type", "PF", "patientId", patient),
                                Map.of()));
        assertEquals(1, result.path("version").asInt());
        verify(access).patient(owner, patient, false);
        verify(events).audit(eq(owner), eq("PayerCreated"), eq("payer"), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2000001})
    void invoiceRejectsInvalidTotals(long total) {
        row(invoice("DRAFT", 18000, 0));
        problem(
                "INVALID_AMOUNT",
                () ->
                        billing.execute(
                                c(
                                        "POST",
                                        "/invoices",
                                        object(
                                                "payerId",
                                                id,
                                                "dueOn",
                                                LocalDate.now().toString(),
                                                "items",
                                                List.of(object("amountCents", total))),
                                        Map.of())));
    }

    @Test
    void invoiceCreationChecksItemPatientAndAuditsAtMaximum() {
        row(invoice("DRAFT", 2000000, 0));
        var b =
                object(
                        "payerId",
                        id,
                        "dueOn",
                        LocalDate.now().toString(),
                        "items",
                        List.of(object("descriptionCode", "ADJUSTMENT", "amountCents", 2000000)));
        var result = billing.execute(c("POST", "/invoices", b, Map.of()));
        assertEquals(2000000, result.path("amountCents").asInt());
        verify(events).audit(eq(owner), eq("InvoiceCreated"), eq("invoice"), any());
        b =
                object(
                        "payerId",
                        id,
                        "dueOn",
                        LocalDate.now().toString(),
                        "items",
                        List.of(
                                object(
                                        "appointmentId",
                                        id,
                                        "descriptionCode",
                                        "SESSION_PRIMARY",
                                        "amountCents",
                                        18000)));
        final var mismatch = b;
        problem(
                "INVOICE_PATIENT_MISMATCH",
                () -> billing.execute(c("POST", "/invoices", mismatch, Map.of())));
    }

    @Test
    void invoiceIssueChecksStatusAccessAndEmitsVersion() {
        var r = invoice("OPEN", 18000, 0);
        row(r);
        var vars = Map.of("invoiceId", id.toString());
        problem(
                "INVALID_TRANSITION",
                () -> billing.execute(c("POST", "/invoices/x/actions/issue", object(), vars)));
        r.put("status", "DRAFT");
        billing.execute(c("POST", "/invoices/x/actions/issue", object(), vars));
        verify(events).emit(owner, "InvoiceIssued", "invoice", id, 2);
        verify(access, times(2)).billing(owner, r, true);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 10001})
    void manualPaymentRejectsOverpaymentAndNonpositive(long value) {
        row(invoice("PARTIALLY_PAID", 18000, 8000));
        problem(
                "INVALID_AMOUNT",
                () ->
                        billing.execute(
                                c(
                                        "POST",
                                        "/invoices/x/manual-payments",
                                        object("amountCents", value, "reason", "Sintético"),
                                        Map.of("invoiceId", id.toString()))));
    }

    @Test
    void manualPaymentDateAndRoleAndAudit() {
        row(invoice("PARTIALLY_PAID", 18000, 8000));
        var vars = Map.of("invoiceId", id.toString());
        var b = object("amountCents", 10000, "reason", "Sintético");
        problem(
                "PERMISSION_DENIED",
                () ->
                        billing.execute(
                                command(
                                        "POST",
                                        "/invoices/x/manual-payments",
                                        b,
                                        outsider(),
                                        vars,
                                        1)));
        problem(
                "INVALID_RECEIVED_DATE",
                () ->
                        billing.execute(
                                c(
                                        "POST",
                                        "/invoices/x/manual-payments",
                                        object(
                                                "amountCents",
                                                10000,
                                                "reason",
                                                "Sintético",
                                                "receivedOn",
                                                LocalDate.now().plusDays(1).toString()),
                                        vars)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(18000L);
        billing.execute(c("POST", "/invoices/x/manual-payments", b, vars));
        verify(events).audit(eq(owner), eq("ManualPaymentRecorded"), eq("payment"), any());
        verify(events).emit(owner, "PaymentStateUpdated", "invoice", id, 2);
    }

    @Test
    void invoiceCancellationAuditsAndRejectsPaidOrPending() {
        var r = invoice("OPEN", 18000, 0);
        row(r);
        var c =
                c(
                        "POST",
                        "/invoices/x/actions/cancel",
                        object(),
                        Map.of("invoiceId", id.toString()));
        billing.execute(c);
        verify(events).audit(owner, "InvoiceCancelled", "invoice", id);
        r.put("paid_cents", 1L);
        problem("INVALID_TRANSITION", () -> billing.execute(c));
        r.put("paid_cents", 0L);
        when(db.exists(anyString(), any(Object[].class))).thenReturn(true);
        problem("PENDING_PAYMENT_REQUIRES_RECONCILIATION", () -> billing.execute(c));
    }

    @Test
    void availabilityChecksRoleRangeAndAudits() {
        var vars = Map.of("professionalId", pro.toString());
        var b =
                object(
                        "weekday",
                        1,
                        "localStart",
                        "09:00",
                        "localEnd",
                        "17:00",
                        "timezone",
                        "America/Sao_Paulo",
                        "effectiveFrom",
                        "2026-10-01");
        problem(
                "PERMISSION_DENIED",
                () ->
                        scheduling.execute(
                                command(
                                        "POST",
                                        "/professionals/x/availability",
                                        b,
                                        outsider(),
                                        vars,
                                        1)));
        var result = scheduling.execute(c("POST", "/professionals/x/availability", b, vars));
        assertEquals(1, result.path("version").asInt());
        verify(events).audit(eq(owner), eq("AvailabilityCreated"), eq("availability"), any());
        problem(
                "INVALID_AVAILABILITY",
                () ->
                        scheduling.execute(
                                c(
                                        "POST",
                                        "/professionals/x/availability",
                                        object("localStart", "17:00", "localEnd", "09:00"),
                                        vars)));
        when(db.rows(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        id,
                                        "weekday",
                                        1,
                                        "local_start",
                                        "09:00",
                                        "local_end",
                                        "17:00")));
        assertEquals(
                "09:00",
                scheduling
                        .execute(c("GET", "/professionals/x/availability", object(), vars))
                        .path("items")
                        .get(0)
                        .path("localStart")
                        .asText());
    }

    @Test
    void exceptionChecksRoleRangeAndAudits() {
        var vars = Map.of("professionalId", pro.toString());
        var start = OffsetDateTime.now();
        var b = object("fromAt", start.toString(), "toAt", start.plusHours(1).toString());
        problem(
                "PERMISSION_DENIED",
                () ->
                        scheduling.execute(
                                command(
                                        "POST",
                                        "/professionals/x/exceptions",
                                        b,
                                        outsider(),
                                        vars,
                                        1)));
        var result = scheduling.execute(c("POST", "/professionals/x/exceptions", b, vars));
        assertEquals(1, result.path("version").asInt());
        verify(events)
                .audit(eq(owner), eq("AvailabilityBlocked"), eq("availabilityException"), any());
        problem(
                "INVALID_AVAILABILITY",
                () ->
                        scheduling.execute(
                                c(
                                        "POST",
                                        "/professionals/x/exceptions",
                                        object(
                                                "fromAt",
                                                start.toString(),
                                                "toAt",
                                                start.toString()),
                                        vars)));
    }

    @Test
    void appointmentTransitionsEnforceWindowAssignmentAndEmitHistory() {
        var now = Instant.now();
        var r =
                new HashMap<String, Object>(
                        Map.of(
                                "id",
                                id,
                                "patient_id",
                                patient,
                                "professional_id",
                                pro,
                                "status",
                                "SCHEDULED",
                                "start_at",
                                now.minusSeconds(1200),
                                "end_at",
                                now.plusSeconds(1800),
                                "version",
                                1));
        row(r);
        when(access.professional(owner)).thenReturn(pro);
        var vars = Map.of("appointmentId", id.toString());
        scheduling.execute(c("POST", "/appointments/x/actions/start", object(), vars));
        verify(events).emit(owner, "AppointmentStarted", "appointment", id, 2);
        verify(access).requireClinical(owner, patient);
        verify(db).exec(contains("app.appointment_history"), any(Object[].class));
        r.put("start_at", now.plusSeconds(3600));
        problem(
                "VIDEO_WINDOW_CLOSED",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/start", object(), vars)));
        problem(
                "NO_SHOW_WINDOW_CLOSED",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/no-show", object(), vars)));
        r.put("start_at", now.minusSeconds(2000));
        r.put("end_at", now.minusSeconds(700000));
        problem(
                "NO_SHOW_WINDOW_CLOSED",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/no-show", object(), vars)));
        r.put("end_at", now.plusSeconds(1000));
        when(access.professional(owner)).thenReturn(UUID.randomUUID());
        problem(
                "RESOURCE_NOT_FOUND",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/start", object(), vars)));
    }

    @Test
    void appointmentConfirmationRequiresOwnPatientAndPreStartTime() {
        var r =
                new HashMap<String, Object>(
                        Map.of(
                                "id",
                                id,
                                "patient_id",
                                patient,
                                "status",
                                "SCHEDULED",
                                "start_at",
                                Instant.now().minusSeconds(1),
                                "end_at",
                                Instant.now().plusSeconds(3000),
                                "version",
                                1));
        row(r);
        var vars = Map.of("appointmentId", id.toString());
        problem(
                "RESOURCE_NOT_FOUND",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/confirm", object(), vars)));
        when(access.portal(owner, patient)).thenReturn(true);
        problem(
                "INVALID_TRANSITION",
                () ->
                        scheduling.execute(
                                c("POST", "/appointments/x/actions/confirm", object(), vars)));
        r.put("status", "CONFIRMED");
        assertEquals(
                "CONFIRMED",
                scheduling
                        .execute(c("POST", "/appointments/x/actions/confirm", object(), vars))
                        .path("status")
                        .asText());
    }

    @Test
    void accessDoesNotBroadenPortalIntoWritesOrOtherPatients() {
        var real = new Access(db);
        var a = outsider();
        var ap = Map.<String, Object>of("patient_id", patient);
        when(db.exists(anyString(), any(Object[].class))).thenReturn(false);
        problem("RESOURCE_NOT_FOUND", () -> real.patient(a, patient, false));
        problem("RESOURCE_NOT_FOUND", () -> real.appointment(a, ap, false));
        when(db.exists(contains("portal_user_id"), any(Object[].class))).thenReturn(true);
        assertDoesNotThrow(() -> real.patient(a, patient, false));
        assertDoesNotThrow(() -> real.appointment(a, ap, false));
        problem("RESOURCE_NOT_FOUND", () -> real.patient(a, patient, true));
        problem("RESOURCE_NOT_FOUND", () -> real.appointment(a, ap, true));
        assertFalse(real.guardian(a, patient, "SCHEDULE"));
        assertTrue(real.portal(a, patient));
    }
}
