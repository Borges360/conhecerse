package com.psicocare.shared;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;

class OutboundHttpTest {
    private static Path testCa() throws Exception {
        return Path.of(OutboundHttpTest.class.getResource("/tls/test-ca.pem").toURI());
    }

    @Test
    void withoutBundleUsesJvmDefaults() throws Exception {
        assertSame(SSLContext.getDefault(), OutboundHttp.build(null));
        assertSame(SSLContext.getDefault(), OutboundHttp.build(" "));
    }

    @Test
    void privateCaIsTrustedInAdditionToDefaults() throws Exception {
        X509Certificate ca;
        try (var input = Files.newInputStream(testCa())) {
            ca =
                    (X509Certificate)
                            CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        var issuers = OutboundHttp.trustManager(testCa().toString()).getAcceptedIssuers();
        assertTrue(Arrays.asList(issuers).contains(ca));
        assertTrue(issuers.length > 1, "JVM default issuers must remain trusted");
        assertNotNull(OutboundHttp.build(testCa().toString()).getSocketFactory());
    }

    @Test
    void misconfiguredBundleFailsClosed(@TempDir Path dir) throws Exception {
        var empty = Files.writeString(dir.resolve("empty.pem"), "");
        assertThrows(IllegalStateException.class, () -> OutboundHttp.build(empty.toString()));
        assertThrows(
                IllegalStateException.class,
                () -> OutboundHttp.build(dir.resolve("missing.pem").toString()));
    }
}
