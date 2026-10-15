package com.psicocare;

import static com.psicocare.shared.Json.*;

import static org.junit.jupiter.api.Assertions.*;

import com.psicocare.communications.DeliveryWebhookController;
import com.psicocare.documents.*;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.ess.*;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.*;
import org.bouncycastle.operator.jcajce.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class SignatureAndCallbackTest {
    @TempDir Path directory;

    @Test
    void callbacksRejectTamperingAndExpiredSvixTimestamps() throws Exception {
        byte[] raw = "{\"type\":\"email.delivered\"}".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var secret = object("webhookSecret", "whsec_" + Base64.getEncoder().encodeToString(key));
        var headers = new HttpHeaders();
        String ts = Long.toString(Instant.now().getEpochSecond());
        headers.set("svix-id", "synthetic-event");
        headers.set("svix-timestamp", ts);
        byte[] prefix = ("synthetic-event." + ts + ".").getBytes(StandardCharsets.UTF_8);
        var data = new ByteArrayOutputStream();
        data.write(prefix);
        data.write(raw);
        headers.set(
                "svix-signature",
                "v1," + Base64.getEncoder().encodeToString(mac(key, data.toByteArray())));
        assertTrue(
                DeliveryWebhookController.authenticate(
                        "EMAIL", secret, headers, raw, Instant.now()));
        assertFalse(
                DeliveryWebhookController.authenticate(
                        "EMAIL", secret, headers, "{}".getBytes(), Instant.now()));
        assertFalse(
                DeliveryWebhookController.authenticate(
                        "EMAIL", secret, headers, raw, Instant.now().plusSeconds(301)));
        var whatsapp = object("appSecret", "SYNTHETIC_APP_SECRET_32_CHARS_LONG_ONLY");
        headers.set(
                "X-Hub-Signature-256",
                "sha256="
                        + HexFormat.of()
                                .formatHex(
                                        mac(
                                                whatsapp.path("appSecret")
                                                        .asText()
                                                        .getBytes(StandardCharsets.UTF_8),
                                                raw)));
        assertTrue(
                DeliveryWebhookController.authenticate(
                        "WHATSAPP", whatsapp, headers, raw, Instant.now()));
        assertFalse(
                DeliveryWebhookController.authenticate(
                        "WHATSAPP", whatsapp, headers, "{}".getBytes(), Instant.now()));
    }

    @Test
    void unsignedPdfRequiresHumanReviewAndMalformedPdfIsRejected() {
        var verifier = new SignatureVerifier("", "", false);
        assertEquals(
                "MANUAL_REVIEW_REQUIRED",
                verifier.verify(DocumentsModule.renderPdf("SYNTHETIC_TEST_ONLY"))
                        .path("status")
                        .asText());
        assertEquals("INVALID", verifier.verify(new byte[] {1, 2, 3}).path("status").asText());
    }

    @Test
    void padesChecksSignatureTrustRevocationAndHomologation() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var name = new X500Name("CN=SYNTHETIC TEST CA ONLY");
        var now = Instant.now();
        var builder =
                new JcaX509v3CertificateBuilder(
                        name,
                        java.math.BigInteger.ONE,
                        Date.from(now.minusSeconds(60)),
                        Date.from(now.plusSeconds(86400)),
                        name,
                        keys.getPublic());
        builder.addExtension(
                org.bouncycastle.asn1.x509.Extension.basicConstraints,
                true,
                new BasicConstraints(true));
        builder.addExtension(
                org.bouncycastle.asn1.x509.Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        X509Certificate cert =
                new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        var crlBuilder =
                new org.bouncycastle.cert.X509v2CRLBuilder(name, Date.from(now.minusSeconds(30)));
        crlBuilder.setNextUpdate(Date.from(now.plusSeconds(3600)));
        Path anchors = directory.resolve("synthetic-ca.pem"),
                crls = directory.resolve("synthetic-crl.pem");
        Files.write(anchors, cert.getEncoded());
        Files.write(crls, crlBuilder.build(signer).getEncoded());
        byte[] signed;
        try (var pdf =
                        org.apache.pdfbox.Loader.loadPDF(
                                DocumentsModule.renderPdf("SYNTHETIC_SIGNED_CONTENT"));
                var output = new ByteArrayOutputStream()) {
            var signature =
                    new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
            signature.setFilter(
                    org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
                            .FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(org.apache.pdfbox.cos.COSName.getPDFName("ETSI.CAdES.detached"));
            signature.setSignDate(Calendar.getInstance());
            pdf.addSignature(
                    signature,
                    content -> {
                        try {
                            var cms = new CMSSignedDataGenerator();
                            var attrs =
                                    new Hashtable<
                                            ASN1ObjectIdentifier,
                                            org.bouncycastle.asn1.cms.Attribute>();
                            attrs.put(
                                    PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                                    new org.bouncycastle.asn1.cms.Attribute(
                                            PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                                            new DERSet(
                                                    new SigningCertificateV2(
                                                            new ESSCertIDv2(
                                                                    FileService.sha(
                                                                            cert.getEncoded()))))));
                            cms.addSignerInfoGenerator(
                                    new JcaSignerInfoGeneratorBuilder(
                                                    new JcaDigestCalculatorProviderBuilder()
                                                            .build())
                                            .setSignedAttributeGenerator(
                                                    new DefaultSignedAttributeTableGenerator(
                                                            new org.bouncycastle.asn1.cms
                                                                    .AttributeTable(attrs)))
                                            .build(signer, cert));
                            cms.addCertificates(new JcaCertStore(List.of(cert)));
                            return cms.generate(
                                            new CMSProcessableByteArray(content.readAllBytes()),
                                            false)
                                    .getEncoded();
                        } catch (Exception e) {
                            throw new IOException(e);
                        }
                    });
            pdf.saveIncremental(output);
            signed = output.toByteArray();
        }
        var untrusted = new SignatureVerifier("", "", false).verify(signed);
        assertTrue(untrusted.path("cryptographicallyValid").asBoolean(), untrusted.toString());
        assertEquals("MANUAL_REVIEW_REQUIRED", untrusted.path("status").asText());
        var trusted =
                new SignatureVerifier(anchors.toString(), crls.toString(), true).verify(signed);
        assertEquals("AUTOMATIC_VALIDATED", trusted.path("status").asText(), trusted.toString());
        assertFalse(trusted.path("legalValidityClaimed").asBoolean());
        byte[] tampered = Arrays.copyOf(signed, signed.length + 1);
        tampered[tampered.length - 1] = 32;
        assertEquals(
                "INVALID",
                new SignatureVerifier(anchors.toString(), crls.toString(), true)
                        .verify(tampered)
                        .path("status")
                        .asText());
        assertEquals(
                "MANUAL_REVIEW_REQUIRED",
                new SignatureVerifier(anchors.toString(), crls.toString(), false)
                        .verify(signed)
                        .path("status")
                        .asText());
        var revokedCrl =
                new org.bouncycastle.cert.X509v2CRLBuilder(name, Date.from(now.minusSeconds(30)));
        revokedCrl.setNextUpdate(Date.from(now.plusSeconds(3600)));
        revokedCrl.addCRLEntry(cert.getSerialNumber(), Date.from(now.minusSeconds(10)), 1);
        Files.write(crls, revokedCrl.build(signer).getEncoded());
        assertEquals(
                "CERTIFICATE_REVOKED",
                new SignatureVerifier(anchors.toString(), crls.toString(), true)
                        .verify(signed)
                        .path("reasonCode")
                        .asText());
    }

    private byte[] mac(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
