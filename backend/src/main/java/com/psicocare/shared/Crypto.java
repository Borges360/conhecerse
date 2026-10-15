package com.psicocare.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
public class Crypto {
    private final String provider, environment, localKey, lookupKey, baoUrl, tokenFile;
    private final SecureRandom random = new SecureRandom();

    public Crypto(
            @Value("${app.encryption.provider}") String provider,
            @Value("${app.environment}") String environment,
            @Value("${app.encryption.local-key}") String localKey,
            @Value("${app.encryption.lookup-key}") String lookupKey,
            @Value("${app.encryption.bao-url}") String baoUrl,
            @Value("${app.encryption.bao-token-file}") String tokenFile) {
        this.provider = provider;
        this.environment = environment;
        this.localKey = localKey;
        this.lookupKey = lookupKey;
        this.baoUrl = baoUrl;
        this.tokenFile = tokenFile;
        Problem.require(
                !environment.equals("production") || provider.equals("openbao"),
                503,
                "KMS_REQUIRED");
        Problem.require(
                !environment.equals("production") || baoUrl.startsWith("https://"),
                503,
                "KMS_TLS_REQUIRED");
    }

    private byte[] randomBytes(int size) {
        var b = new byte[size];
        random.nextBytes(b);
        return b;
    }

    private byte[] aes(int mode, byte[] key, byte[] iv, byte[] data, String aad) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new Problem(503, "ENCRYPTION_UNAVAILABLE");
        }
    }

    private String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private byte[] un64(String b) {
        return Base64.getDecoder().decode(b);
    }

    private JsonNode bao(String path, JsonNode body) {
        try {
            String token = Files.readString(Path.of(tokenFile)).trim();
            String result =
                    OutboundHttp.client(baoUrl)
                            .post()
                            .uri("/v1/" + path)
                            .header("X-Vault-Token", token)
                            .body(body)
                            .retrieve()
                            .body(String.class);
            return Json.parse(result).path("data");
        } catch (Exception e) {
            throw new Problem(503, "KMS_UNAVAILABLE");
        }
    }

    public JsonNode secret(String reference) {
        Problem.require(
                reference.matches("conhecerse/(dev|staging|production)/[a-zA-Z0-9/_-]+"),
                422,
                "INVALID_SECRET_REFERENCE");
        try {
            String token = Files.readString(Path.of(tokenFile)).trim();
            String data =
                    OutboundHttp.client(baoUrl)
                            .get()
                            .uri("/v1/secret/data/" + reference)
                            .header("X-Vault-Token", token)
                            .retrieve()
                            .body(String.class);
            return Json.parse(data).path("data").path("data");
        } catch (Exception e) {
            throw new Problem(503, "SECRET_UNAVAILABLE");
        }
    }

    public String videoKey(String room) {
        Problem.require(provider.equals("openbao"), 503, "VIDEO_KMS_REQUIRED");
        String hmac =
                bao(
                                "transit/hmac/conhecerse-video/sha2-256",
                                Json.object("input", b64(room.getBytes(StandardCharsets.UTF_8))))
                        .path("hmac")
                        .asText();
        String encoded = hmac.substring(hmac.lastIndexOf(':') + 1);
        return b64(HexFormat.of().parseHex(encoded));
    }

    public byte[] encrypt(UUID tenant, String table, UUID id, int revision, String value) {
        return encryptBytes(tenant, table, id, revision, value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] encryptBytes(UUID tenant, String table, UUID id, int revision, byte[] value) {
        String aad = tenant + ":" + table + ":" + id + ":" + revision;
        var dek = randomBytes(32);
        var iv = randomBytes(12);
        String wrapped;
        try {
            if (provider.equals("openbao"))
                wrapped =
                        bao(
                                        "transit/encrypt/conhecerse",
                                        Json.object(
                                                "plaintext",
                                                b64(dek),
                                                "context",
                                                b64(
                                                        tenant.toString()
                                                                .getBytes(StandardCharsets.UTF_8))))
                                .path("ciphertext")
                                .asText();
            else {
                Problem.require(!environment.equals("production"), 503, "KMS_REQUIRED");
                var wrapIv = randomBytes(12);
                wrapped =
                        "local:"
                                + b64(wrapIv)
                                + ":"
                                + b64(
                                        aes(
                                                Cipher.ENCRYPT_MODE,
                                                un64(localKey),
                                                wrapIv,
                                                dek,
                                                tenant.toString()));
            }
            return Json.object(
                            "v",
                            1,
                            "key",
                            wrapped,
                            "iv",
                            b64(iv),
                            "data",
                            b64(aes(Cipher.ENCRYPT_MODE, dek, iv, value, aad)))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decryptBytes(UUID tenant, String table, UUID id, int revision, byte[] value) {
        if (value == null) return new byte[0];
        var envelope = Json.parse(new String(value, StandardCharsets.UTF_8));
        String wrapped = envelope.path("key").asText();
        byte[] dek;
        if (wrapped.startsWith("local:")) {
            Problem.require(!environment.equals("production"), 503, "KMS_REQUIRED");
            var parts = wrapped.split(":");
            dek =
                    aes(
                            Cipher.DECRYPT_MODE,
                            un64(localKey),
                            un64(parts[1]),
                            un64(parts[2]),
                            tenant.toString());
        } else
            dek =
                    un64(
                            bao(
                                            "transit/decrypt/conhecerse",
                                            Json.object(
                                                    "ciphertext",
                                                    wrapped,
                                                    "context",
                                                    b64(
                                                            tenant.toString()
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))))
                                    .path("plaintext")
                                    .asText());
        try {
            return aes(
                    Cipher.DECRYPT_MODE,
                    dek,
                    un64(envelope.path("iv").asText()),
                    un64(envelope.path("data").asText()),
                    tenant + ":" + table + ":" + id + ":" + revision);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public String decrypt(UUID tenant, String table, UUID id, int revision, byte[] value) {
        return new String(decryptBytes(tenant, table, id, revision, value), StandardCharsets.UTF_8);
    }

    public byte[] lookup(UUID tenant, String value) {
        try {
            var key = un64(lookupKey);
            Problem.require(key.length >= 32, 503, "LOOKUP_KEY_REQUIRED");
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(
                    (tenant + ":" + value.strip().toLowerCase(Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8));
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(503, "LOOKUP_UNAVAILABLE");
        }
    }
}
