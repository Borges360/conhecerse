package com.psicocare;

import com.psicocare.documents.FileService;
import com.psicocare.shared.*;

import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
public class FileDownloadController {
    private final Store db;
    private final FileService files;

    public FileDownloadController(Store db, FileService files) {
        this.db = db;
        this.files = files;
    }

    @GetMapping("/v1/orgs/{orgId}/files/{fileId}/bytes")
    @Transactional
    public ResponseEntity<byte[]> download(
            @PathVariable UUID orgId,
            @PathVariable UUID fileId,
            @RequestParam String ticket,
            JwtAuthenticationToken authentication) {
        UUID user =
                db.jdbc.queryForObject(
                        "select app.ensure_user(?)",
                        UUID.class,
                        authentication.getToken().getSubject());
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, orgId.toString());
        db.jdbc.queryForObject(
                "select set_config('app.actor_id',?,true)", String.class, user.toString());
        Problem.require(
                db.exists("select 1 from app.organizations where id=? and status='ACTIVE'", orgId),
                404,
                "RESOURCE_NOT_FOUND");
        Set<String> roles = new HashSet<>();
        db.optional(
                        "select roles::text[] from app.organization_memberships where tenant_id=?"
                                + " and user_id=? and status='ACTIVE'",
                        orgId,
                        user)
                .ifPresent(
                        r -> {
                            try {
                                roles.addAll(
                                        Arrays.asList(
                                                (String[])
                                                        ((java.sql.Array) r.get("roles"))
                                                                .getArray()));
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
        var jwt = authentication.getToken();
        var amr = jwt.getClaimAsStringList("amr");
        Instant mfa =
                ((amr != null && (amr.contains("otp") || amr.contains("mfa")))
                                || "2".equals(jwt.getClaimAsString("acr")))
                        ? jwt.getClaimAsInstant("auth_time")
                        : null;
        if (!roles.isEmpty()) Problem.require(mfa != null, 403, "STAFF_MFA_REQUIRED");
        boolean portal =
                db.exists(
                        "select 1 from app.patients where tenant_id=? and portal_user_id=?",
                        orgId,
                        user);
        boolean guardian =
                db.exists(
                        "select 1 from app.guardian_patient_access where tenant_id=? and"
                            + " guardian_user_id=? and legal_verified_at is not null and not"
                            + " review_required and valid_from<=now() and (valid_until is null or"
                            + " valid_until>now())",
                        orgId,
                        user);
        Problem.require(!roles.isEmpty() || portal || guardian, 404, "RESOURCE_NOT_FOUND");
        byte[] data =
                files.download(
                        new Actor(orgId, user, roles, mfa, UUID.randomUUID()), fileId, ticket);
        String mime =
                Store.str(
                        db.one(
                                "select mime_type from app.file_assets where tenant_id=? and id=?",
                                orgId,
                                fileId),
                        "mime_type");
        String extension =
                switch (mime) {
                    case "application/pdf" -> "pdf";
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "application/json" -> "json";
                    default -> "bin";
                };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"documento." + extension + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }
}
