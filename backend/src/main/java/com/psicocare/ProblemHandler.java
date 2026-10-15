package com.psicocare;

import com.psicocare.shared.*;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

import java.util.*;

@RestControllerAdvice
public class ProblemHandler {
    private static final org.slf4j.Logger SECURITY =
            org.slf4j.LoggerFactory.getLogger("security.events");

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonNode> problem(Exception e) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (e instanceof Problem p) {
            status = p.status;
            code = p.code;
        } else if (e instanceof java.time.DateTimeException
                || e instanceof IllegalArgumentException) {
            status = 422;
            code = "VALIDATION_FAILED";
        } else if (e instanceof DataAccessException) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String state = cause instanceof java.sql.SQLException s ? s.getSQLState() : "";
            if ("23P01".equals(state)) {
                status = 409;
                code = "SLOT_UNAVAILABLE";
            } else if ("40P01".equals(state) || "40001".equals(state)) {
                status = 409;
                code = "CONCURRENT_CONFLICT";
            } else if ("23505".equals(state)) {
                status = 409;
                code = "DUPLICATE_RESOURCE";
            } else if ("23503".equals(state) || "23514".equals(state) || "22P02".equals(state)) {
                status = 422;
                code = "VALIDATION_FAILED";
            } else if ("42501".equals(state)) {
                status = 404;
                code = "RESOURCE_NOT_FOUND";
            } else if ("P0001".equals(state)) {
                status = 409;
                code = "IMMUTABLE_RESOURCE";
            }
        }
        UUID requestId = UUID.randomUUID();
        // No exception messages, request URLs, headers or user-provided values reach telemetry.
        if (status == 401 || status == 403 || status == 404)
            SECURITY.warn("access_denied code={} status={} requestId={}", code, status, requestId);
        var result =
                Json.object(
                        "type",
                        "urn:conhecerse:problem:" + code.toLowerCase(Locale.ROOT),
                        "title",
                        message(code),
                        "status",
                        status,
                        "code",
                        code,
                        "detail",
                        message(code),
                        "requestId",
                        requestId,
                        "violations",
                        List.of());
        var response =
                ResponseEntity.status(status)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .cacheControl(CacheControl.noStore());
        if (status == 429) response.header("Retry-After", "60");
        return response.body(result);
    }

    private String message(String code) {
        return switch (code) {
            case "REMOTE_PROTOCOL_REVIEW_REQUIRED" ->
                    "Registre e revise o protocolo de atendimento remoto antes de entrar na sala.";
            case "DOMAIN_APPROVAL_REQUIRED",
                    "SECURITY_APPROVAL_REQUIRED",
                    "SIGNATURE_APPROVAL_REQUIRED",
                    "BUSINESS_APPROVAL_REQUIRED",
                    "COMMUNICATION_APPROVAL_REQUIRED" ->
                    "O fluxo aguarda homologação do responsável por esta área.";
            case "INDEPENDENT_REVIEW_REQUIRED" ->
                    "A aprovação exige outra pessoa autorizada, independente da solicitação e da"
                        + " primeira revisão.";
            case "DAILY_LIMIT_REACHED" ->
                    "O limite de atendimentos deste profissional para o dia foi atingido.";
            case "HOLIDAY_CLOSED", "ORGANIZATION_CLOSED" ->
                    "A clínica está fechada nesse dia ou horário.";
            case "PLATFORM_BILLING_NOT_CONFIGURED" ->
                    "Cadastre a configuração de cobrança da plataforma no cofre para consultar os"
                        + " planos.";
            case "SUBSCRIPTION_STATE_UNKNOWN" ->
                    "A criação da assinatura está incerta. Confira a referência no provedor antes"
                        + " de reprocessar.";
            case "RECIPIENT_SUPPRESSED" ->
                    "Este destinatário foi suprimido após uma falha de entrega ou reclamação.";
            case "EXPORT_EXPIRED", "DOWNLOAD_LINK_EXPIRED" ->
                    "O prazo deste link ou pacote terminou. Solicite nova liberação ao"
                        + " profissional.";
            case "AUTOMATIC_SIGNATURE_UNVERIFIED", "INVALID_CRYPTOGRAPHIC_SIGNATURE" ->
                    "A verificação não confirmou a assinatura. Confira o arquivo e a evidência"
                        + " antes da emissão.";
            case "SLOT_UNAVAILABLE" -> "Esse horário já está ocupado. Escolha outro horário.";
            case "STALE_VERSION" -> "Este registro foi atualizado. Recarregue antes de salvar.";
            case "STAFF_MFA_REQUIRED", "RECENT_MFA_REQUIRED" ->
                    "Confirme sua identidade com o segundo fator.";
            case "PERMISSION_DENIED", "RESOURCE_NOT_FOUND" -> "Você não tem acesso a este recurso.";
            case "INVALID_TRANSITION" -> "Esta ação não está disponível no estado atual.";
            case "INTERNAL_ERROR" ->
                    "Não foi possível concluir. Tente novamente com o identificador da"
                            + " solicitação.";
            case "VERIFIED_PROFESSIONAL_REQUIRED" ->
                    "Cadastre e revise o registro profissional antes de agendar.";
            case "PRICE_REQUIRED" ->
                    "Defina o preço vigente ou informe o valor acordado para o encontro.";
            case "PAYMENT_PROVIDER_NOT_APPROVED" ->
                    "A cobrança online aguarda configuração e homologação do consultório.";
            case "VIDEO_NOT_APPROVED", "VIDEO_UNAVAILABLE" ->
                    "A sala está indisponível. Use o canal de contingência combinado com o"
                            + " consultório.";
            case "TEMPLATE_APPROVAL_REQUIRED" ->
                    "O modelo precisa de revisão e aprovação antes do uso.";
            default -> "Não foi possível concluir. Confira os dados informados e tente novamente.";
        };
    }
}
