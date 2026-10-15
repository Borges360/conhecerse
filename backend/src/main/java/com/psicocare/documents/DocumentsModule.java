package com.psicocare.documents;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.io.*;
import java.util.*;

@Service
public class DocumentsModule implements ModuleHandler {
    private final Store db;
    private final Crypto crypto;
    private final Access access;
    private final Events events;
    private final FileService files;
    private final SignatureVerifier verifier;
    private final boolean enabled;

    public DocumentsModule(
            Store db,
            Crypto crypto,
            Access access,
            Events events,
            FileService files,
            SignatureVerifier verifier,
            @Value("${app.templates-approved}") boolean enabled) {
        this.db = db;
        this.crypto = crypto;
        this.access = access;
        this.events = events;
        this.files = files;
        this.verifier = verifier;
        this.enabled = enabled;
    }

    public boolean supports(Command c) {
        return Set.of("Documents", "Files", "ClinicalAssets").contains(c.tag())
                || c.path().endsWith("/portal/documents");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        UUID tenant = a.tenant();
        var b = c.body();
        String path = c.path();
        if (c.tag().equals("ClinicalAssets")) {
            UUID patient = c.id("patientId");
            access.requireClinical(a, patient);
            boolean restricted = path.endsWith("/restricted-test-materials");
            String table = restricted ? "restricted_test_materials" : "clinical_files";
            if (c.isGet())
                return c.page(
                        db
                                .rows(
                                        "select id,file_asset_id,created_at from app."
                                                + table
                                                + " where tenant_id=? and patient_id=? order by"
                                                + " created_at desc",
                                        tenant,
                                        patient)
                                .stream()
                                .map(r -> dto(r, "id", "file_asset_id", "created_at"))
                                .toList());
            UUID file = uuid(b, "fileId");
            var asset = files.authorized(a, file, false);
            Problem.require(
                    str(asset, "status").equals("READY")
                            && str(asset, "malware_scan").equals("CLEAN")
                            && Objects.equals(asset.get("owner_patient_id"), patient)
                            && str(asset, "classification")
                                    .equals(restricted ? "RESTRICTED_TEST" : "SENSITIVE_CLINICAL"),
                    422,
                    "FILE_CLASSIFICATION_MISMATCH");
            UUID id = UUID.randomUUID();
            if (restricted) {
                String key =
                        crypto.decrypt(
                                tenant,
                                "file_assets.key",
                                file,
                                1,
                                (byte[]) asset.get("object_key_cipher"));
                db.exec(
                        "insert into"
                            + " app.restricted_test_materials(tenant_id,id,patient_id,professional_id,file_asset_id,object_key_cipher,storage_sha256,encrypted_key_ref)"
                            + " values(?,?,?,?,?,?,?,'tenant-envelope')",
                        tenant,
                        id,
                        patient,
                        access.professional(a),
                        file,
                        crypto.encrypt(tenant, "restricted_test_materials.key", id, 1, key),
                        asset.get("checksum_sha256"));
            } else
                db.exec(
                        "insert into"
                            + " app.clinical_files(tenant_id,id,patient_id,file_asset_id,added_by)"
                            + " values(?,?,?,?,?)",
                        tenant,
                        id,
                        patient,
                        file,
                        a.user());
            events.audit(
                    a,
                    restricted ? "RestrictedMaterialAttached" : "ClinicalFileAttached",
                    restricted ? "restrictedMaterial" : "clinicalFile",
                    id);
            return object("id", id, "version", 1);
        }
        if (c.tag().equals("Files")) {
            if (path.endsWith("/uploads")) return files.initialize(a, b);
            if (path.endsWith("/complete")) return files.complete(a, c.id("fileId"));
            return files.ticket(a, c.id("fileId"));
        }
        if (path.contains("/document-templates")) {
            if (c.isGet()) {
                a.require("OWNER", "ADMIN", "PSYCHOLOGIST", "SECRETARY");
                return c.page(
                        db
                                .rows(
                                        "select * from app.document_templates where tenant_id=?"
                                                + " order by doc_type,version",
                                        tenant)
                                .stream()
                                .map(
                                        r ->
                                                dto(
                                                        r,
                                                        "id",
                                                        "doc_type",
                                                        "version",
                                                        "status",
                                                        "approved_by",
                                                        "approved_at"))
                                .toList());
            }
            if (path.endsWith("/approve")) {
                a.require("OWNER", "ADMIN");
                a.recentMfa();
                Problem.require(
                        db.exists(
                                "select 1 from app.release_approvals where tenant_id=? and"
                                        + " gate_code='GATE-CLINICAL-01'",
                                tenant),
                        422,
                        "TEMPLATE_APPROVAL_REQUIRED");
                UUID id = c.id("templateId");
                db.exec(
                        "update app.document_templates set"
                            + " status='APPROVED',approved_by=?,approved_at=now() where tenant_id=?"
                            + " and id=? and status='DRAFT'",
                        a.user(),
                        tenant,
                        id);
                events.audit(a, "DocumentTemplateApproved", "documentTemplate", id);
                return object("id", id, "version", 1);
            }
            a.require("PSYCHOLOGIST");
            UUID id = UUID.randomUUID();
            db.exec(
                    "insert into"
                        + " app.document_templates(tenant_id,id,doc_type,version,body_template_cipher,author_user_id)"
                        + " values(?,?,?,?,?,?)",
                    tenant,
                    id,
                    text(b, "type"),
                    text(b, "version"),
                    crypto.encrypt(tenant, "document_templates", id, 1, text(b, "content")),
                    a.user());
            events.audit(a, "DocumentTemplateCreated", "documentTemplate", id);
            return object("id", id, "version", 1);
        }
        if (c.vars().containsKey("documentId")) {
            UUID id = c.id("documentId");
            var d =
                    db.one(
                            "select * from app.documents where tenant_id=? and id=?"
                                    + (c.isGet() ? "" : " for update"),
                            tenant,
                            id);
            if (c.isGet()) return document(d);
            access.requireClinical(a, id(d, "patient_id"));
            Problem.require(a.user().equals(d.get("author_user_id")), 404, "RESOURCE_NOT_FOUND");
            String status = str(d, "status");
            if (c.tail().equals("verify-signature")) {
                a.recentMfa();
                var asset = files.authorized(a, uuid(b, "fileId"), false);
                Problem.require(
                        Objects.equals(asset.get("owner_patient_id"), d.get("patient_id"))
                                && str(asset, "classification").equals("SENSITIVE_CLINICAL")
                                && str(asset, "mime_type").equals("application/pdf"),
                        422,
                        "FILE_CLASSIFICATION_MISMATCH");
                var result = verifier.verify(files.validatedBytes(a, uuid(b, "fileId")));
                UUID check = UUID.randomUUID();
                db.exec(
                        "insert into"
                            + " app.document_signature_checks(tenant_id,id,document_id,file_id,checked_by,result)"
                            + " values(?,?,?,?,?,?::jsonb)",
                        tenant,
                        check,
                        id,
                        uuid(b, "fileId"),
                        a.user(),
                        result.toString());
                events.audit(a, "DocumentSignatureVerified", "document", id);
                return object("items", List.of(result), "nextCursor", null);
            }
            if (Set.of("request-signature", "submit-signature", "publish").contains(c.tail()))
                Problem.require(enabled, 503, "TEMPLATES_NOT_APPROVED");
            switch (c.tail()) {
                case "render-draft" -> {
                    Problem.require(status.equals("DRAFT"), 409, "INVALID_TRANSITION");
                    String content =
                            crypto.decrypt(
                                    tenant, "documents", id, 1, (byte[]) d.get("payload_cipher"));
                    UUID file = files.saveDraft(a, id(d, "patient_id"), pdf(content));
                    return files.ticket(a, file);
                }
                case "request-signature" -> {
                    Problem.require(status.equals("DRAFT"), 409, "INVALID_TRANSITION");
                    Problem.require(
                            db.exists(
                                    "select 1 from app.document_templates where tenant_id=? and"
                                            + " doc_type=? and version=? and status='APPROVED'",
                                    tenant,
                                    str(d, "type"),
                                    str(d, "template_version")),
                            422,
                            "TEMPLATE_APPROVAL_REQUIRED");
                    db.exec(
                            "update app.documents set status='PENDING_SIGNATURE',version=version+1"
                                    + " where tenant_id=? and id=?",
                            tenant,
                            id);
                }
                case "submit-signature" -> {
                    a.recentMfa();
                    Problem.require(status.equals("PENDING_SIGNATURE"), 409, "INVALID_TRANSITION");
                    var file = files.authorized(a, uuid(b, "signedFileId"), false);
                    Problem.require(
                            str(file, "classification").equals("SENSITIVE_CLINICAL")
                                    && str(file, "status").equals("READY")
                                    && str(file, "mime_type").equals("application/pdf")
                                    && str(file, "malware_scan").equals("CLEAN")
                                    && Objects.equals(
                                            file.get("owner_patient_id"), d.get("patient_id")),
                            422,
                            "UNVERIFIED_SIGNED_PDF");
                    var verification =
                            verifier.verify(files.validatedBytes(a, uuid(b, "signedFileId")));
                    UUID check = UUID.randomUUID();
                    db.exec(
                            "insert into"
                                + " app.document_signature_checks(tenant_id,id,document_id,file_id,checked_by,result)"
                                + " values(?,?,?,?,?,?::jsonb)",
                            tenant,
                            check,
                            id,
                            uuid(b, "signedFileId"),
                            a.user(),
                            verification.toString());
                    boolean automatic = text(b, "verificationStatus").equals("AUTOMATIC_VALIDATED");
                    if (automatic)
                        Problem.require(
                                db.exists(
                                        "select 1 from app.release_approvals where tenant_id=? and"
                                            + " gate_code='GATE-SIGNATURE-01'",
                                        tenant),
                                422,
                                "SIGNATURE_APPROVAL_REQUIRED");
                    Problem.require(
                            !automatic
                                    || verification
                                            .path("status")
                                            .asText()
                                            .equals("AUTOMATIC_VALIDATED"),
                            422,
                            "AUTOMATIC_SIGNATURE_UNVERIFIED");
                    Problem.require(
                            !verification.path("status").asText().equals("INVALID"),
                            422,
                            "INVALID_CRYPTOGRAPHIC_SIGNATURE");
                    Problem.require(
                            automatic || text(b, "verificationEvidence").length() >= 8,
                            422,
                            "SIGNATURE_REVIEW_REQUIRED");
                    db.exec(
                            "update app.documents set"
                                + " status='ISSUED',signed_file_asset_id=?,content_sha256=?,issued_at=now(),signature_provider=?,external_signature_ref=?,signature_verification_evidence=?,version=version+1"
                                + " where tenant_id=? and id=?",
                            uuid(b, "signedFileId"),
                            file.get("original_checksum_sha256"),
                            text(b, "signatureMethod"),
                            automatic ? "AUTOMATIC_VALIDATED" : "MANUAL_REVIEWED",
                            text(b, "verificationEvidence"),
                            tenant,
                            id);
                    events.emit(a, "DocumentIssued", "document", id, version(d) + 1);
                }
                case "publish" -> {
                    Problem.require(status.equals("ISSUED"), 409, "INVALID_TRANSITION");
                    db.exec(
                            "update app.documents set published_to_patient_at=now() where"
                                    + " tenant_id=? and id=? and published_to_patient_at is null",
                            tenant,
                            id);
                    events.audit(a, "DocumentPublishedToPatient", "document", id);
                }
                case "revoke" -> {
                    Problem.require(status.equals("ISSUED"), 409, "INVALID_TRANSITION");
                    db.exec(
                            "update app.documents set status='REVOKED' where tenant_id=? and id=?",
                            tenant,
                            id);
                    events.emit(a, "DocumentRevoked", "document", id, version(d));
                }
                default -> throw Problem.notFound();
            }
            return document(
                    db.one("select * from app.documents where tenant_id=? and id=?", tenant, id));
        }
        if (c.isGet())
            return c.page(
                    db
                            .rows(
                                    "select * from app.documents where tenant_id=? order by"
                                            + " created_at desc",
                                    tenant)
                            .stream()
                            .map(this::document)
                            .toList());
        UUID patient = uuid(b, "patientId"), id = UUID.randomUUID();
        access.requireClinical(a, patient);
        db.exec(
                "insert into"
                    + " app.documents(tenant_id,id,patient_id,author_user_id,type,template_version,payload_cipher)"
                    + " values(?,?,?,?,?,?,?)",
                tenant,
                id,
                patient,
                a.user(),
                text(b, "type"),
                text(b, "templateVersion"),
                crypto.encrypt(tenant, "documents", id, 1, text(b, "content")));
        events.audit(a, "DocumentDraftCreated", "document", id);
        return document(
                db.one("select * from app.documents where tenant_id=? and id=?", tenant, id));
    }

    private JsonNode document(Map<String, Object> r) {
        var result =
                (tools.jackson.databind.node.ObjectNode)
                        dto(
                                r,
                                "id",
                                "patient_id",
                                "type",
                                "status",
                                "version",
                                "issued_at",
                                "published_to_patient_at",
                                "signed_file_asset_id");
        result.put(
                "downloadAvailable",
                r.get("published_to_patient_at") != null && str(r, "status").equals("ISSUED"));
        return result;
    }

    private byte[] pdf(String text) {
        return renderPdf("RASCUNHO — SEM ASSINATURA — NÃO OFICIAL\n" + text);
    }

    public static byte[] renderPdf(String text) {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
                var output = new ByteArrayOutputStream()) {
            var font =
                    org.apache.pdfbox.pdmodel.font.PDType0Font.load(
                            doc,
                            DocumentsModule.class.getResourceAsStream(
                                    "/fonts/Montserrat-Regular.ttf"));
            var lines = new ArrayList<String>();
            for (String paragraph : text.split("\\R")) {
                var line = new StringBuilder();
                for (int cp : paragraph.codePoints().toArray()) {
                    String ch = new String(Character.toChars(cp));
                    try {
                        font.getStringWidth(ch);
                    } catch (IllegalArgumentException e) {
                        throw new Problem(422, "UNSUPPORTED_PDF_CHARACTER");
                    }
                    if (font.getStringWidth(line.toString() + ch) * 11 / 1000 > 498) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(ch);
                }
                lines.add(line.toString());
            }
            for (int offset = 0; offset < lines.size(); offset += 46) {
                var page =
                        new org.apache.pdfbox.pdmodel.PDPage(
                                org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                doc.addPage(page);
                try (var canvas = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    canvas.beginText();
                    canvas.setFont(font, 11);
                    canvas.newLineAtOffset(48, 780);
                    for (String line : lines.subList(offset, Math.min(offset + 46, lines.size()))) {
                        canvas.showText(line);
                        canvas.newLineAtOffset(0, -15);
                    }
                    canvas.endText();
                }
            }
            doc.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new Problem(503, "PDF_UNAVAILABLE");
        }
    }
}
