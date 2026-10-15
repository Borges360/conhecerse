package com.psicocare.receipts;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Service
public class ReceiptsModule implements ModuleHandler {
    private final Store db;
    private final Access access;
    private final Events events;
    private final Crypto crypto;
    private final boolean enabled;

    public ReceiptsModule(
            Store db,
            Access access,
            Events events,
            Crypto crypto,
            @Value("${app.fiscal-approved}") boolean enabled) {
        this.db = db;
        this.access = access;
        this.events = events;
        this.crypto = crypto;
        this.enabled = enabled;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Receipts");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        if (c.isGet()) {
            a.require("OWNER", "ADMIN", "BILLING", "PSYCHOLOGIST");
            return c.page(
                    db
                            .rows(
                                    "select * from app.receipt_references where tenant_id=? order"
                                            + " by created_at desc",
                                    tenant)
                            .stream()
                            .filter(
                                    r ->
                                            a.has("OWNER", "ADMIN", "BILLING")
                                                    || access.clinical(a, id(r, "patient_id")))
                            .map(r -> reference(tenant, r))
                            .toList());
        }
        Problem.require(enabled, 503, "FISCAL_NOT_APPROVED");
        UUID rid = c.id("receiptId");
        var r =
                db.one(
                        "select * from app.receipt_references where tenant_id=? and id=? for"
                                + " update",
                        tenant,
                        rid);
        Problem.require(
                a.has("OWNER", "ADMIN") || access.clinical(a, id(r, "patient_id")),
                404,
                "RESOURCE_NOT_FOUND");
        Problem.require(
                db.exists(
                        "select 1 from app.release_approvals where tenant_id=? and"
                                + " gate_code='GATE-FISCAL-01'",
                        tenant),
                422,
                "FISCAL_REVIEW_REQUIRED");
        Problem.require(
                Set.of("RECEIPT_PENDING", "REVIEW_REQUIRED").contains(str(r, "status")),
                409,
                "INVALID_TRANSITION");
        var p =
                db.one(
                        "select * from app.payments where tenant_id=? and id=?",
                        tenant,
                        r.get("payment_id"));
        var i =
                db.one(
                        "select * from app.invoices where tenant_id=? and id=?",
                        tenant,
                        p.get("invoice_id"));
        LocalDate date = LocalDate.parse(text(b, "externalIssuedOn"));
        Problem.require(
                !date.isAfter(LocalDate.now()) && date.getYear() >= 2000,
                422,
                "EXTERNAL_RECEIPT_NOT_VERIFIED");
        Problem.require(
                str(p, "status").equals("SETTLED")
                        && b.path("amountCents").asLong()
                                == ((Number) p.get("received_cents")).longValue()
                        && uuid(b, "payerId").equals(id(i, "payer_id")),
                422,
                "EXTERNAL_RECEIPT_NOT_VERIFIED");
        if (b.has("evidenceFileId"))
            Problem.require(
                    db.exists(
                            "select 1 from app.file_assets where tenant_id=? and id=? and"
                                    + " status='READY' and malware_scan='CLEAN' and"
                                    + " classification='FINANCIAL'",
                            tenant,
                            uuid(b, "evidenceFileId")),
                    422,
                    "UNVERIFIED_EVIDENCE_FILE");
        db.exec(
                "update app.receipt_references set"
                    + " status='EXTERNAL_ISSUED',official_receipt_id=?,external_issued_on=?,amount_cents=?,payer_id=?,declared_by=?,evidence_file_id=?,version=version+1"
                    + " where tenant_id=? and id=?",
                text(b, "officialReceiptId"),
                date,
                b.path("amountCents").asLong(),
                uuid(b, "payerId"),
                a.user(),
                b.has("evidenceFileId") ? uuid(b, "evidenceFileId") : null,
                tenant,
                rid);
        events.audit(a, "ExternalReceiptDeclared", "receiptReference", rid);
        return dto(
                db.one(
                        "select * from app.receipt_references where tenant_id=? and id=?",
                        tenant,
                        rid),
                "id",
                "payment_id",
                "patient_id",
                "payer_id",
                "status",
                "official_receipt_id",
                "external_issued_on",
                "amount_cents",
                "version");
    }

    private JsonNode reference(UUID tenant, Map<String, Object> r) {
        var result =
                (tools.jackson.databind.node.ObjectNode)
                        dto(
                                r,
                                "id",
                                "payment_id",
                                "patient_id",
                                "payer_id",
                                "status",
                                "official_receipt_id",
                                "external_issued_on",
                                "amount_cents",
                                "version");
        if (r.get("payer_id") != null) {
            var payer =
                    db.one(
                            "select * from app.invoice_payers where tenant_id=? and id=?",
                            tenant,
                            r.get("payer_id"));
            UUID id = id(payer, "id");
            result.put(
                    "payerName",
                    crypto.decrypt(
                            tenant,
                            "invoice_payers.name",
                            id,
                            1,
                            (byte[]) payer.get("name_cipher")));
            result.put(
                    "payerTaxId",
                    crypto.decrypt(
                            tenant,
                            "invoice_payers.tax",
                            id,
                            1,
                            (byte[]) payer.get("tax_id_cipher")));
        }
        if (r.get("patient_id") != null) {
            var patient =
                    db.one(
                            "select * from app.patients where tenant_id=? and id=?",
                            tenant,
                            r.get("patient_id"));
            UUID id = id(patient, "id");
            result.put(
                    "beneficiaryName",
                    crypto.decrypt(
                            tenant,
                            "patients.name",
                            id,
                            version(patient),
                            (byte[]) patient.get("name_cipher")));
            result.put(
                    "beneficiaryCpf",
                    crypto.decrypt(
                            tenant,
                            "patients.cpf",
                            id,
                            version(patient),
                            (byte[]) patient.get("cpf_cipher")));
        }
        return result;
    }
}
