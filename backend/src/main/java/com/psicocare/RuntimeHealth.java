package com.psicocare;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.*;

@Configuration
public class RuntimeHealth {
    @Bean
    ApplicationRunner verifyRuntimeRole(
            Store db,
            @Value("${app.environment}") String environment,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${app.storage.endpoint}") String storage) {
        return args -> {
            Problem.require(
                    !environment.equals("production")
                            || (url.contains("sslmode=verify-full")
                                    && issuer.startsWith("https://")
                                    && storage.startsWith("https://")),
                    503,
                    "PRODUCTION_TLS_REQUIRED");
            var r =
                    db.one(
                            "select r.rolsuper,r.rolbypassrls,exists(select 1 from pg_class c join"
                                + " pg_namespace n on n.oid=c.relnamespace where n.nspname='app'"
                                + " and c.relkind='r' and c.relowner=r.oid) as owns_tables from"
                                + " pg_roles r where r.rolname=current_user");
            Problem.require(
                    !Boolean.TRUE.equals(r.get("rolsuper"))
                            && !Boolean.TRUE.equals(r.get("rolbypassrls"))
                            && !Boolean.TRUE.equals(r.get("owns_tables")),
                    503,
                    "UNSAFE_DATABASE_ROLE");
        };
    }

    @Bean(name = "kms")
    HealthIndicator kms(
            @Value("${app.encryption.provider}") String provider,
            @Value("${app.encryption.bao-token-file}") String tokenFile,
            @Value("${app.encryption.bao-url}") String baoUrl) {
        return () -> {
            if (provider.equals("local")) return Health.up().build();
            try {
                if (!Files.isReadable(Path.of(tokenFile))) return Health.down().build();
                var response =
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(2))
                                .build()
                                .send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        java.net.URI.create(
                                                                baoUrl + "/v1/sys/health"))
                                                .timeout(java.time.Duration.ofSeconds(3))
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) return Health.down().build();
                var auth =
                        java.net.http.HttpClient.newHttpClient()
                                .send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        java.net.URI.create(
                                                                baoUrl
                                                                        + "/v1/auth/token/lookup-self"))
                                                .timeout(java.time.Duration.ofSeconds(3))
                                                .header(
                                                        "X-Vault-Token",
                                                        Files.readString(Path.of(tokenFile)).trim())
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding());
                return auth.statusCode() == 200 ? Health.up().build() : Health.down().build();
            } catch (Exception e) {
                return Health.down().build();
            }
        };
    }
}
