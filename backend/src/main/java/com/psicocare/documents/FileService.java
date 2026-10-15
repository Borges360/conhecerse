package com.psicocare.documents;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.*;

import tools.jackson.databind.JsonNode;

import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class FileService {
    private final Store db;
    private final Crypto crypto;
    private final Access access;
    private final Events events;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket, publicUrl, clamHost;
    private final int clamPort;

    public FileService(
            Store db,
            Crypto crypto,
            Access access,
            Events events,
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.public-url}") String publicUrl,
            @Value("${S3_PUBLIC_ENDPOINT:}") String publicEndpoint,
            @Value("${CLAMAV_HOST:localhost}") String clamHost,
            @Value("${CLAMAV_PORT:3310}") int clamPort) {
        this.db = db;
        this.crypto = crypto;
        this.access = access;
        this.events = events;
        this.bucket = bucket;
        this.publicUrl = publicUrl;
        this.clamHost = clamHost;
        this.clamPort = clamPort;
        s3 =
                S3Client.builder()
                        .overrideConfiguration(
                                c ->
                                        c.apiCallTimeout(Duration.ofSeconds(30))
                                                .apiCallAttemptTimeout(Duration.ofSeconds(15)))
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .forcePathStyle(true)
                        .build();
        presigner =
                S3Presigner.builder()
                        .endpointOverride(
                                URI.create(publicEndpoint.isBlank() ? endpoint : publicEndpoint))
                        .region(Region.of(region))
                        .serviceConfiguration(
                                S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build();
    }

    public JsonNode initialize(Actor a, JsonNode body) {
        UUID id = UUID.randomUUID(),
                patient = body.has("patientId") ? uuid(body, "patientId") : null;
        String classification = text(body, "classification");
        if (Set.of("SENSITIVE_CLINICAL", "RESTRICTED_TEST").contains(classification)) {
            Problem.require(patient != null, 422, "PATIENT_REQUIRED");
            access.requireClinical(a, patient);
        } else if (classification.equals("FINANCIAL")) a.require("OWNER", "ADMIN", "BILLING");
        else a.require("OWNER", "ADMIN");
        String mime = text(body, "contentType");
        Problem.require(
                Set.of("application/pdf", "image/png", "image/jpeg").contains(mime),
                422,
                "UNSUPPORTED_UPLOAD");
        String key = "quarantine/" + a.tenant() + "/" + id;
        db.exec(
                "insert into"
                    + " app.file_assets(tenant_id,id,owner_patient_id,classification,object_key_cipher,filename_cipher,mime_type,size_bytes,checksum_sha256,kms_key_ref,malware_scan,uploaded_by)"
                    + " values(?,?,?,?,?,?,?,?,?,'tenant-envelope','PENDING',?)",
                a.tenant(),
                id,
                patient,
                classification,
                crypto.encrypt(a.tenant(), "file_assets.key", id, 1, key),
                crypto.encrypt(
                        a.tenant(),
                        "file_assets.filename",
                        id,
                        1,
                        "documento"
                                + (mime.equals("application/pdf")
                                        ? ".pdf"
                                        : mime.equals("image/png") ? ".png" : ".jpg")),
                mime,
                body.path("sizeBytes").asLong(),
                HexFormat.of().parseHex(text(body, "sha256")),
                a.user());
        var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime).build();
        var signed =
                presigner.presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(10))
                                .putObjectRequest(put)
                                .build());
        return object(
                "id",
                id,
                "uploadUrl",
                signed.url().toString(),
                "expiresAt",
                Instant.now().plusSeconds(600).toString());
    }

    public JsonNode complete(Actor a, UUID id) {
        authorized(a, id, true);
        var file =
                db.one(
                        "select * from app.file_assets where tenant_id=? and id=? for update",
                        a.tenant(),
                        id);
        Problem.require(str(file, "status").equals("UPLOADING"), 409, "INVALID_TRANSITION");
        String key = key(a.tenant(), file);
        byte[] data = read(key);
        try {
            Problem.require(
                    data.length == ((Number) file.get("size_bytes")).longValue()
                            && MessageDigest.isEqual(
                                    sha(data), (byte[]) file.get("checksum_sha256")),
                    422,
                    "FILE_CHECKSUM_MISMATCH");
            String detected = detect(data);
            Problem.require(detected.equals(str(file, "mime_type")), 422, "UNSUPPORTED_UPLOAD");
            scan(data);
            if (detected.equals("application/pdf")) safePdf(data);
        } catch (Problem invalid) {
            if (invalid.status != 422) throw invalid;
            db.exec(
                    "update app.file_assets set status='REJECTED',malware_scan='REJECTED' where"
                        + " tenant_id=? and id=?",
                    a.tenant(),
                    id);
            events.audit(a, "FileRejected", "file", id);
            return object(
                    "id", id, "version", 1, "status", "REJECTED", "failureCode", invalid.code);
        }
        String finalKey = a.tenant() + "/" + id + "/encrypted";
        byte[] encrypted = crypto.encryptBytes(a.tenant(), "file_assets.content", id, 1, data);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(finalKey)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(encrypted));
        db.exec(
                "update app.file_assets set"
                    + " object_key_cipher=?,original_checksum_sha256=checksum_sha256,checksum_sha256=?,malware_scan='CLEAN',status='READY'"
                    + " where tenant_id=? and id=?",
                crypto.encrypt(a.tenant(), "file_assets.key", id, 1, finalKey),
                sha(encrypted),
                a.tenant(),
                id);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        events.audit(a, "FileValidated", "file", id);
        return object("id", id, "version", 1, "status", "READY");
    }

    public JsonNode ticket(Actor a, UUID id) {
        var file = authorized(a, id, false);
        Problem.require(
                str(file, "status").equals("READY") && str(file, "malware_scan").equals("CLEAN"),
                422,
                "FILE_NOT_READY");
        var random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.exec(
                "insert into app.download_tickets(tenant_id,file_id,actor_id,token_hash,expires_at)"
                        + " values(?,?,?,?,now()+interval '60 seconds')",
                a.tenant(),
                id,
                a.user(),
                hash(token));
        events.audit(a, "FileDownloadAuthorized", "file", id);
        return object(
                "id",
                id,
                "downloadUrl",
                publicUrl
                        + "/api/bff/v1/orgs/"
                        + a.tenant()
                        + "/files/"
                        + id
                        + "/bytes?ticket="
                        + token,
                "expiresAt",
                Instant.now().plusSeconds(60).toString());
    }

    public byte[] download(Actor a, UUID id, String token) {
        var ticket =
                db.optional(
                                "select * from app.download_tickets where tenant_id=? and file_id=?"
                                    + " and actor_id=? and token_hash=? and expires_at>now() and"
                                    + " consumed_at is null for update",
                                a.tenant(),
                                id,
                                a.user(),
                                hash(token))
                        .orElseThrow(() -> new Problem(403, "DOWNLOAD_LINK_EXPIRED"));
        var file = authorized(a, id, false);
        db.exec(
                "update app.download_tickets set consumed_at=now() where tenant_id=? and id=?",
                a.tenant(),
                ticket.get("id"));
        byte[] data =
                crypto.decryptBytes(
                        a.tenant(), "file_assets.content", id, 1, read(key(a.tenant(), file)));
        for (var export :
                db.rows(
                        "select * from app.record_exports where tenant_id=? and file_id=? and"
                            + " recipient_user_id=? and status='RELEASED' and expires_at>now() for"
                            + " update",
                        a.tenant(),
                        id,
                        a.user())) {
            db.exec(
                    "update app.record_exports set status='DELIVERED',delivered_at=now() where"
                        + " tenant_id=? and id=?",
                    a.tenant(),
                    export.get("id"));
            db.exec(
                    "update app.privacy_requests set"
                        + " status='FULFILLED',completed_at=now(),decision_code='AUTHENTICATED_DELIVERY',version=version+1"
                        + " where tenant_id=? and id=?",
                    a.tenant(),
                    export.get("request_id"));
            events.audit(a, "RecordExportDelivered", "recordExport", id(export, "id"));
        }
        return data;
    }

    public UUID saveDraft(Actor a, UUID patient, byte[] pdf) {
        return saveExport(a, patient, pdf, "application/pdf");
    }

    public UUID saveExport(Actor a, UUID patient, byte[] pdf, String mime) {
        access.requireClinical(a, patient);
        Problem.require(pdf.length <= 26214400, 422, "REQUEST_TOO_LARGE");
        if (mime.equals("application/pdf")) safePdf(pdf);
        else Problem.require(mime.equals("application/json"), 422, "UNSUPPORTED_UPLOAD");
        scan(pdf);
        UUID id = UUID.randomUUID();
        String key = a.tenant() + "/" + id + "/encrypted";
        byte[] encrypted = crypto.encryptBytes(a.tenant(), "file_assets.content", id, 1, pdf);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(encrypted));
        db.exec(
                "insert into"
                    + " app.file_assets(tenant_id,id,owner_patient_id,classification,object_key_cipher,mime_type,size_bytes,checksum_sha256,original_checksum_sha256,kms_key_ref,malware_scan,status,uploaded_by)"
                    + " values(?,?,?,'SENSITIVE_CLINICAL',?,?,?,"
                    + " ?,?,'tenant-envelope','CLEAN','READY',?)",
                a.tenant(),
                id,
                patient,
                crypto.encrypt(a.tenant(), "file_assets.key", id, 1, key),
                mime,
                pdf.length,
                sha(encrypted),
                sha(pdf),
                a.user());
        return id;
    }

    public byte[] validatedBytes(Actor a, UUID id) {
        var file = authorized(a, id, false);
        Problem.require(
                str(file, "status").equals("READY") && str(file, "malware_scan").equals("CLEAN"),
                422,
                "FILE_NOT_READY");
        return crypto.decryptBytes(
                a.tenant(), "file_assets.content", id, 1, read(key(a.tenant(), file)));
    }

    public Map<String, Object> authorized(Actor a, UUID id, boolean write) {
        var f = db.one("select * from app.file_assets where tenant_id=? and id=?", a.tenant(), id);
        String cls = str(f, "classification");
        if (write)
            Problem.require(a.user().equals(f.get("uploaded_by")), 404, "RESOURCE_NOT_FOUND");
        if (Set.of("SENSITIVE_CLINICAL", "RESTRICTED_TEST").contains(cls)) {
            if (cls.equals("RESTRICTED_TEST")) access.requireClinical(a, id(f, "owner_patient_id"));
            if (!access.clinical(a, id(f, "owner_patient_id")))
                Problem.require(
                        !write
                                && Boolean.TRUE.equals(
                                        db.jdbc.queryForObject(
                                                "select app.can_read_patient_asset(?,?)",
                                                Boolean.class,
                                                id,
                                                f.get("owner_patient_id"))),
                        404,
                        "RESOURCE_NOT_FOUND");
        } else if (!(cls.equals("FINANCIAL")
                ? a.has("OWNER", "ADMIN", "BILLING")
                : a.has("OWNER", "ADMIN")))
            Problem.require(
                    !write
                            && db.exists(
                                    "select 1 from app.content_entries ce join app.content_shares"
                                        + " cs on cs.tenant_id=ce.tenant_id and cs.content_id=ce.id"
                                        + " join app.patients p on p.tenant_id=cs.tenant_id and"
                                        + " p.id=cs.patient_id where ce.tenant_id=? and"
                                        + " ce.file_asset_id=? and ce.status='PUBLISHED' and"
                                        + " p.portal_user_id=? and (cs.valid_until is null or"
                                        + " cs.valid_until>now())",
                                    a.tenant(),
                                    id,
                                    a.user()),
                    404,
                    "RESOURCE_NOT_FOUND");
        return f;
    }

    private String key(UUID tenant, Map<String, Object> file) {
        return crypto.decrypt(
                tenant,
                "file_assets.key",
                id(file, "id"),
                1,
                (byte[]) file.get("object_key_cipher"));
    }

    private byte[] read(String key) {
        try (var stream =
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            var data = stream.readNBytes(40 * 1024 * 1024);
            Problem.require(stream.read() == -1, 422, "UNSUPPORTED_UPLOAD");
            return data;
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(503, "STORAGE_UNAVAILABLE");
        }
    }

    public static byte[] sha(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String detect(byte[] data) {
        if (data.length >= 5
                && new String(data, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("%PDF-")) return "application/pdf";
        if (data.length >= 8
                && Arrays.equals(
                        Arrays.copyOf(data, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (data.length >= 3
                && (data[0] & 255) == 255
                && (data[1] & 255) == 216
                && (data[2] & 255) == 255) return "image/jpeg";
        throw new Problem(422, "UNSUPPORTED_UPLOAD");
    }

    private void scan(byte[] data) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamHost, clamPort), 3000);
            socket.setSoTimeout(15000);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for (int i = 0; i < data.length; i += 8192) {
                int len = Math.min(8192, data.length - i);
                out.writeInt(len);
                out.write(data, i, len);
            }
            out.writeInt(0);
            out.flush();
            var response = new ByteArrayOutputStream();
            for (int ch; (ch = socket.getInputStream().read()) > 0; ) {
                if (response.size() > 1024) throw new IOException();
                response.write(ch);
            }
            Problem.require(
                    response.toString(java.nio.charset.StandardCharsets.US_ASCII).endsWith("OK"),
                    422,
                    "MALWARE_DETECTED");
        } catch (Problem e) {
            throw e;
        } catch (IOException e) {
            throw new Problem(503, "MALWARE_SCANNER_UNAVAILABLE");
        }
    }

    public static void safePdf(byte[] data) {
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(data)) {
            Problem.require(!pdf.isEncrypted(), 422, "UNSUPPORTED_UPLOAD");
            var seen =
                    Collections.newSetFromMap(
                            new IdentityHashMap<org.apache.pdfbox.cos.COSBase, Boolean>());
            inspect(pdf.getDocumentCatalog().getCOSObject(), seen);
        } catch (Problem e) {
            throw e;
        } catch (IOException e) {
            throw new Problem(422, "UNSUPPORTED_UPLOAD");
        }
    }

    private static void inspect(
            org.apache.pdfbox.cos.COSBase base, Set<org.apache.pdfbox.cos.COSBase> seen) {
        if (base == null || !seen.add(base)) return;
        Problem.require(seen.size() < 100000, 422, "UNSUPPORTED_UPLOAD");
        if (base instanceof org.apache.pdfbox.cos.COSObject o) inspect(o.getObject(), seen);
        if (base instanceof org.apache.pdfbox.cos.COSDictionary d)
            for (var entry : d.entrySet()) {
                Problem.require(
                        !Set.of(
                                        "JavaScript",
                                        "JS",
                                        "Launch",
                                        "EmbeddedFiles",
                                        "OpenAction",
                                        "AA",
                                        "URI",
                                        "RichMedia",
                                        "XFA")
                                .contains(entry.getKey().getName()),
                        422,
                        "UNSUPPORTED_UPLOAD");
                inspect(entry.getValue(), seen);
            }
        if (base instanceof org.apache.pdfbox.cos.COSArray a)
            for (var item : a) inspect(item, seen);
    }
}
