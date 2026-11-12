package com.psicocare.shared;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Bounded calls without logging headers, bodies or provider error payloads. */
public final class OutboundHttp {
    /** Optional PEM bundle of private CAs trusted in addition to the JVM defaults, e.g. OpenBao. */
    static final String EXTRA_CA_ENV = "APP_EXTRA_CA_FILE";

    private static volatile SSLContext context;

    private OutboundHttp() {}

    public static RestClient client(String baseUrl) {
        var factory =
                new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(HttpURLConnection connection, String method)
                            throws IOException {
                        if (connection instanceof HttpsURLConnection https) {
                            try {
                                https.setSSLSocketFactory(sslContext().getSocketFactory());
                            } catch (IllegalStateException e) {
                                throw new IOException("Outbound TLS trust unavailable", e);
                            }
                        }
                        super.prepareConnection(connection, method);
                    }
                };
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * JVM default trust plus {@value #EXTRA_CA_ENV}, when configured. An unreadable or empty bundle
     * fails every TLS call instead of falling back to the defaults, so a misconfigured private CA
     * is never silently ignored.
     */
    public static SSLContext sslContext() {
        var current = context;
        if (current == null) {
            current = build(System.getenv(EXTRA_CA_ENV));
            context = current;
        }
        return current;
    }

    static SSLContext build(String caFile) {
        try {
            if (caFile == null || caFile.isBlank()) return SSLContext.getDefault();
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, new javax.net.ssl.TrustManager[] {trustManager(caFile)}, null);
            return tls;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Outbound TLS trust unavailable", e);
        }
    }

    static X509TrustManager trustManager(String caFile) {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            var defaults =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            int index = 0;
            for (var manager : defaults.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        store.setCertificateEntry("default-" + index++, issuer);
                    }
                }
            }
            int extra = 0;
            try (InputStream input = Files.newInputStream(Path.of(caFile))) {
                for (var certificate :
                        CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                    store.setCertificateEntry("extra-" + extra++, certificate);
                }
            }
            if (extra == 0) throw new IllegalStateException("Empty outbound CA bundle");
            var factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) return x509;
            }
            throw new IllegalStateException("No X.509 trust manager");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Outbound TLS trust unavailable", e);
        }
    }
}
