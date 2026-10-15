package com.psicocare.integrations;

import com.psicocare.shared.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class WebhookAuthentication extends OncePerRequestFilter {
    private static final ThreadLocal<UUID> AUTHENTICATED = new ThreadLocal<>();
    private final Store db;
    private final Crypto crypto;
    private final TransactionTemplate tx;

    public WebhookAuthentication(Store db, Crypto crypto, TransactionTemplate tx) {
        this.db = db;
        this.crypto = crypto;
        this.tx = tx;
    }

    public static boolean isAuthenticated(UUID id) {
        return id.equals(AUTHENTICATED.get());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/v1/webhooks/asaas/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            UUID id =
                    UUID.fromString(
                            request.getRequestURI()
                                    .substring(request.getRequestURI().lastIndexOf('/') + 1));
            String supplied = request.getHeader("asaas-access-token");
            boolean valid =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    status -> {
                                        UUID tenant =
                                                Store.id(
                                                        db.one(
                                                                "select tenant_id from"
                                                                        + " app.integration_routes"
                                                                        + " where id=? and"
                                                                        + " provider='ASAAS'",
                                                                id),
                                                        "tenant_id");
                                        db.jdbc.queryForObject(
                                                "select set_config('app.tenant_id',?,true)",
                                                String.class,
                                                tenant.toString());
                                        var c =
                                                db.one(
                                                        "select secrets_path from"
                                                            + " app.integration_connections where"
                                                            + " tenant_id=? and"
                                                            + " webhook_identifier=? and"
                                                            + " status='ACTIVE'",
                                                        tenant,
                                                        id);
                                        String expected =
                                                crypto.secret(Store.str(c, "secrets_path"))
                                                        .path("webhookToken")
                                                        .asText();
                                        return supplied != null
                                                && expected.length() >= 32
                                                && MessageDigest.isEqual(
                                                        supplied.getBytes(StandardCharsets.UTF_8),
                                                        expected.getBytes(StandardCharsets.UTF_8));
                                    }));
            if (!valid) throw new Problem(401, "INVALID_WEBHOOK_SECRET");
            AUTHENTICATED.set(id);
            chain.doFilter(request, response);
        } catch (Problem | IllegalArgumentException e) {
            int status = e instanceof Problem p && p.status >= 500 ? 503 : 401;
            response.setStatus(status);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write(
                            Json.object(
                                            "type",
                                            "about:blank",
                                            "title",
                                            status == 503
                                                    ? "Autenticação temporariamente indisponível"
                                                    : "Autenticação do provedor inválida",
                                            "status",
                                            status,
                                            "code",
                                            status == 503
                                                    ? "WEBHOOK_AUTH_UNAVAILABLE"
                                                    : "INVALID_WEBHOOK_SECRET",
                                            "requestId",
                                            UUID.randomUUID())
                                    .toString());
        } finally {
            AUTHENTICATED.remove();
        }
    }
}
