package com.psicocare;

import static org.junit.jupiter.api.Assertions.*;

import com.psicocare.content.ContentModule;
import com.psicocare.documents.FileService;
import com.psicocare.scheduling.SchedulingModule;
import com.psicocare.shared.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.*;

class DomainTest {
    @ParameterizedTest(name = "T-007 terminal {0} rejects {1}")
    @CsvSource({
        "COMPLETED,accept",
        "COMPLETED,cancel",
        "COMPLETED,start",
        "NO_SHOW,confirm",
        "NO_SHOW,complete",
        "CANCELLED,start",
        "CANCELLED,cancel",
        "IN_PROGRESS,cancel",
        "REQUESTED,complete",
        "SCHEDULED,complete"
    })
    void terminalStates(String state, String command) {
        assertEquals(
                "INVALID_TRANSITION",
                assertThrows(Problem.class, () -> SchedulingModule.next(state, command)).code);
    }

    @ParameterizedTest(name = "SCH transition {0} / {1} -> {2}")
    @CsvSource({
        "REQUESTED,accept,SCHEDULED",
        "REQUESTED,reject,CANCELLED",
        "SCHEDULED,confirm,CONFIRMED",
        "CONFIRMED,confirm,CONFIRMED",
        "SCHEDULED,start,IN_PROGRESS",
        "CONFIRMED,start,IN_PROGRESS",
        "IN_PROGRESS,complete,COMPLETED",
        "CONFIRMED,no-show,NO_SHOW",
        "SCHEDULED,cancel,CANCELLED"
    })
    void allowedStates(String from, String command, String to) {
        assertEquals(to, SchedulingModule.next(from, command));
    }

    @Test
    void T037_dstGapAndAmbiguity() {
        var zone = ZoneId.of("America/New_York");
        assertThrows(
                Problem.class,
                () ->
                        SchedulingModule.resolveLocal(
                                LocalDateTime.parse("2026-03-08T02:30"), zone, null));
        assertThrows(
                Problem.class,
                () ->
                        SchedulingModule.resolveLocal(
                                LocalDateTime.parse("2026-11-01T01:30"), zone, null));
        assertEquals(
                ZoneOffset.ofHours(-5),
                SchedulingModule.resolveLocal(
                                LocalDateTime.parse("2026-11-01T01:30"),
                                zone,
                                ZoneOffset.ofHours(-5))
                        .getOffset());
    }

    @Test
    void T037_preservesLocalClock() {
        var zone = ZoneId.of("America/New_York");
        var before =
                SchedulingModule.resolveLocal(LocalDateTime.parse("2026-03-01T09:00"), zone, null);
        var after =
                SchedulingModule.resolveLocal(LocalDateTime.parse("2026-03-15T09:00"), zone, null);
        assertEquals(before.toLocalTime(), after.toLocalTime());
        assertNotEquals(before.getOffset(), after.getOffset());
    }

    @ParameterizedTest(name = "SCH rejects duration {0}")
    @ValueSource(ints = {0, 15, 29, 31, 181, 240})
    void invalidDuration(int minutes) {
        var start = OffsetDateTime.now();
        assertThrows(
                Problem.class, () -> SchedulingModule.duration(start, start.plusMinutes(minutes)));
    }

    @Test
    void T015_rejectsExecutable() {
        assertThrows(Problem.class, () -> FileService.detect(new byte[] {77, 90, 0, 0, 0}));
        assertEquals("application/pdf", FileService.detect("%PDF-1.7".getBytes()));
    }

    @Test
    void T036_templateSensitiveFields() {
        assertThrows(
                Problem.class, () -> ContentModule.validateMarketing("Olá {{clinical.narrative}}"));
        assertThrows(Problem.class, () -> ContentModule.validateMarketing("CPF 123.456.789-00"));
    }

    @Test
    void CNT_xssSanitization() {
        String output =
                ContentModule.sanitize(
                        "<script>alert(1)</script><p onclick='attack()'>Reflexão</p><a"
                                + " href='javascript:attack()'>link</a><img"
                                + " src='https://tracker.example/patient'>");
        assertFalse(output.contains("<script"));
        assertFalse(output.contains("onclick"));
        assertFalse(output.contains("javascript:"));
        assertFalse(output.contains("<img"));
        assertTrue(output.contains("Reflexão"));
    }

    @Test
    void CLI_envelopeBindsTenantRowRevision() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var lookup = new byte[32];
        Arrays.fill(lookup, (byte) 5);
        var crypto =
                new Crypto(
                        "local", "test", key, Base64.getEncoder().encodeToString(lookup), "", "");
        UUID t = UUID.randomUUID(), r = UUID.randomUUID();
        byte[] cipher = crypto.encrypt(t, "clinical_notes", r, 3, "CANARY_CLINICAL_SYNTHETIC");
        assertFalse(new String(cipher).contains("CANARY_CLINICAL"));
        assertEquals(
                "CANARY_CLINICAL_SYNTHETIC", crypto.decrypt(t, "clinical_notes", r, 3, cipher));
        assertThrows(
                Problem.class,
                () -> crypto.decrypt(UUID.randomUUID(), "clinical_notes", r, 3, cipher));
        assertThrows(Problem.class, () -> crypto.decrypt(t, "clinical_notes", r, 4, cipher));
        assertNotEquals(
                HexFormat.of().formatHex(crypto.lookup(t, "12345678900")),
                HexFormat.of().formatHex(crypto.lookup(UUID.randomUUID(), "12345678900")));
    }

    @Test
    void SEC_productionRejectsLocalKms() {
        assertThrows(Problem.class, () -> new Crypto("local", "production", "", "", "", ""));
    }

    @Test
    void DOC_draftPdfPreservesPortugueseAndMarksUnsigned() throws Exception {
        var module =
                new com.psicocare.documents.DocumentsModule(
                        null, null, null, null, null, null, false);
        byte[] bytes =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        module,
                        "pdf",
                        "Não é um documento assinado. Informação sintética, atenção e"
                                + " acolhimento.");
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertTrue(text.contains("NÃO OFICIAL"));
            assertTrue(text.contains("Informação sintética"));
            assertEquals(1, pdf.getNumberOfPages());
        }
    }

    @Test
    void architectureIsAcyclic() {
        org.springframework.modulith.core.ApplicationModules.of(PlatformApplication.class).verify();
    }
}
