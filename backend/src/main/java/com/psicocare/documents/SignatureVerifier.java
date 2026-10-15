package com.psicocare.documents;

import static com.psicocare.shared.Json.*;

import org.apache.pdfbox.Loader;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Verifies a supported single-signature PAdES PDF. Legal validity is never inferred. */
@Service
public class SignatureVerifier {
    private final String anchorsPath, crlsPath;
    private final boolean homologated;

    public SignatureVerifier(
            @Value("${ICP_BRASIL_TRUST_ANCHORS:}") String anchorsPath,
            @Value("${ICP_BRASIL_CRLS:}") String crlsPath,
            @Value("${SIGNATURE_VALIDATION_APPROVED:false}") boolean homologated) {
        this.anchorsPath = anchorsPath;
        this.crlsPath = crlsPath;
        this.homologated = homologated;
    }

    public JsonNode verify(byte[] bytes) {
        String hash = HexFormat.of().formatHex(FileService.sha(bytes));
        try (var pdf = Loader.loadPDF(bytes)) {
            var signatures = pdf.getSignatureDictionaries();
            if (signatures.size() != 1)
                return result(
                        hash,
                        "MANUAL_REVIEW_REQUIRED",
                        "UNSUPPORTED_SIGNATURE_COUNT",
                        false,
                        false);
            var signature = signatures.getFirst();
            var range = signature.getByteRange();
            boolean covers =
                    range.length == 4
                            && range[0] == 0
                            && range[1] > 0
                            && range[2] > range[1]
                            && range[3] > 0
                            && (long) range[2] + range[3] == bytes.length;
            if (!covers) return result(hash, "INVALID", "INCOMPLETE_BYTE_RANGE", false, false);
            if (!"ETSI.CAdES.detached".equals(signature.getSubFilter()))
                return result(
                        hash, "MANUAL_REVIEW_REQUIRED", "UNSUPPORTED_PADES_PROFILE", false, false);
            byte[] cmsBytes = signature.getContents(bytes);
            org.bouncycastle.asn1.ASN1Primitive cmsObject;
            try (var input = new java.io.ByteArrayInputStream(cmsBytes);
                    var asn1 = new org.bouncycastle.asn1.ASN1InputStream(input)) {
                cmsObject = asn1.readObject();
                for (int value; (value = input.read()) != -1; )
                    if (value != 0)
                        return result(hash, "INVALID", "NONZERO_SIGNATURE_PADDING", false, false);
            }
            // The signed gap must be exactly the hex Contents string, with no hidden unsigned data.
            if (bytes[range[1]] != '<' || bytes[range[2] - 1] != '>')
                return result(hash, "INVALID", "UNSIGNED_GAP", false, false);
            for (int i = range[1] + 1; i < range[2] - 1; i++)
                if (Character.digit((char) bytes[i], 16) < 0
                        && !Character.isWhitespace((char) bytes[i]))
                    return result(hash, "INVALID", "UNSIGNED_GAP", false, false);
            var cms =
                    new CMSSignedData(
                            new CMSProcessableByteArray(signature.getSignedContent(bytes)),
                            org.bouncycastle.asn1.cms.ContentInfo.getInstance(cmsObject));
            var signers = cms.getSignerInfos().getSigners();
            if (signers.size() != 1)
                return result(
                        hash, "MANUAL_REVIEW_REQUIRED", "UNSUPPORTED_SIGNER_COUNT", false, false);
            var signer = signers.iterator().next();
            if (!Set.of(
                            "2.16.840.1.101.3.4.2.1",
                            "2.16.840.1.101.3.4.2.2",
                            "2.16.840.1.101.3.4.2.3")
                    .contains(signer.getDigestAlgOID()))
                return result(hash, "INVALID", "WEAK_DIGEST", false, false);
            var matching = cms.getCertificates().getMatches(signer.getSID());
            if (matching.size() != 1)
                return result(hash, "INVALID", "SIGNER_CERTIFICATE_MISSING", false, false);
            X509CertificateHolder holder = (X509CertificateHolder) matching.iterator().next();
            var cert = new JcaX509CertificateConverter().getCertificate(holder);
            var attributes = signer.getSignedAttributes();
            if (attributes == null
                    || attributes.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2) == null)
                return result(
                        hash,
                        "MANUAL_REVIEW_REQUIRED",
                        "CADES_CERTIFICATE_BINDING_MISSING",
                        false,
                        false);
            var binding =
                    SigningCertificateV2.getInstance(
                                    attributes
                                            .get(PKCSObjectIdentifiers.id_aa_signingCertificateV2)
                                            .getAttrValues()
                                            .getObjectAt(0))
                            .getCerts()[0];
            if (!binding.getHashAlgorithm().getAlgorithm().getId().equals("2.16.840.1.101.3.4.2.1")
                    || !MessageDigest.isEqual(
                            binding.getCertHash(), FileService.sha(cert.getEncoded())))
                return result(hash, "INVALID", "SIGNER_BINDING_MISMATCH", false, false);
            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(cert)))
                return result(hash, "INVALID", "CRYPTOGRAPHIC_SIGNATURE_INVALID", false, false);
            try {
                cert.checkValidity();
            } catch (CertificateException e) {
                return result(
                        hash,
                        "MANUAL_REVIEW_REQUIRED",
                        "CERTIFICATE_EXPIRED_OR_NOT_YET_VALID",
                        true,
                        false);
            }
            if (cert.getKeyUsage() != null && !cert.getKeyUsage()[0] && !cert.getKeyUsage()[1])
                return result(hash, "INVALID", "SIGNING_KEY_USAGE_REQUIRED", true, false);
            if (anchorsPath.isBlank() || crlsPath.isBlank())
                return result(
                        hash,
                        "MANUAL_REVIEW_REQUIRED",
                        "TRUST_AND_REVOCATION_NOT_CONFIGURED",
                        true,
                        false);
            var factory = CertificateFactory.getInstance("X.509");
            var anchors = new HashSet<TrustAnchor>();
            try (var input = Files.newInputStream(Path.of(anchorsPath))) {
                for (var root : factory.generateCertificates(input))
                    anchors.add(new TrustAnchor((X509Certificate) root, null));
            }
            var certs = new ArrayList<X509Certificate>();
            for (Object c : cms.getCertificates().getMatches(null))
                certs.add(
                        new JcaX509CertificateConverter()
                                .getCertificate((X509CertificateHolder) c));
            var material = new ArrayList<Object>(certs);
            try (var input = Files.newInputStream(Path.of(crlsPath))) {
                material.addAll(factory.generateCRLs(input));
            }
            // CRLs are supplied by the operator. Never fetch certificate-controlled URLs.
            if (Boolean.getBoolean("com.sun.security.enableAIAcaIssuers")
                    || Boolean.getBoolean("com.sun.security.enableCRLDP"))
                return result(
                        hash, "MANUAL_REVIEW_REQUIRED", "OFFLINE_VALIDATION_REQUIRED", true, false);
            var target = new X509CertSelector();
            target.setCertificate(cert);
            var params = new PKIXBuilderParameters(anchors, target);
            params.setRevocationEnabled(false);
            params.addCertStore(
                    CertStore.getInstance(
                            "Collection", new CollectionCertStoreParameters(material)));
            var builder = CertPathBuilder.getInstance("PKIX");
            var chain = (PKIXCertPathBuilderResult) builder.build(params);
            var path =
                    new ArrayList<java.security.cert.Certificate>(
                            chain.getCertPath().getCertificates());
            if (path.isEmpty()) path.add(cert);
            for (int i = 0; i < path.size(); i++) {
                var current = (X509Certificate) path.get(i);
                var issuer =
                        i + 1 < path.size()
                                ? (X509Certificate) path.get(i + 1)
                                : chain.getTrustAnchor().getTrustedCert();
                boolean checked = false;
                for (Object item : material)
                    if (item instanceof X509CRL crl
                            && crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())
                            && crl.getThisUpdate().before(new Date())
                            && crl.getNextUpdate() != null
                            && crl.getNextUpdate().after(new Date())
                            && (crl.getCriticalExtensionOIDs() == null
                                    || crl.getCriticalExtensionOIDs().isEmpty())) {
                        try {
                            crl.verify(issuer.getPublicKey());
                        } catch (GeneralSecurityException invalid) {
                            continue;
                        }
                        if (crl.getRevokedCertificate(current.getSerialNumber()) != null)
                            return result(hash, "INVALID", "CERTIFICATE_REVOKED", true, false);
                        checked = true;
                    }
                if (!checked)
                    return result(
                            hash,
                            "MANUAL_REVIEW_REQUIRED",
                            "CURRENT_FULL_CRL_REQUIRED",
                            true,
                            false);
            }
            var result =
                    result(
                            hash,
                            homologated ? "AUTOMATIC_VALIDATED" : "MANUAL_REVIEW_REQUIRED",
                            homologated
                                    ? "TRUSTED_PADES_WITH_CURRENT_REVOCATION"
                                    : "VALIDATOR_NOT_HOMOLOGATED",
                            true,
                            true);
            ((tools.jackson.databind.node.ObjectNode) result)
                    .put(
                            "signerCertificateSha256",
                            HexFormat.of().formatHex(FileService.sha(cert.getEncoded())));
            return result;
        } catch (CertPathBuilderException e) {
            return result(
                    hash, "MANUAL_REVIEW_REQUIRED", "TRUST_OR_REVOCATION_UNVERIFIED", true, false);
        } catch (Exception e) {
            return result(hash, "INVALID", "SIGNATURE_OR_PDF_INVALID", false, false);
        }
    }

    private JsonNode result(
            String hash, String status, String reason, boolean crypto, boolean trust) {
        return object(
                "status",
                status,
                "reasonCode",
                reason,
                "fileSha256",
                hash,
                "cryptographicallyValid",
                crypto,
                "trustedChainAndRevocation",
                trust,
                "legalValidityClaimed",
                false,
                "checkedAt",
                java.time.Instant.now().toString());
    }
}
