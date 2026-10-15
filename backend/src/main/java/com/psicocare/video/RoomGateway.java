package com.psicocare.video;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.psicocare.shared.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** LiveKit Twirp API. Room names and JWT subjects contain only opaque identifiers. */
@Component
public class RoomGateway {
    private final String endpoint, key, secret;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public RoomGateway(
            @Value("${app.video.signaling-url}") String signaling,
            @Value("${app.video.api-key}") String key,
            @Value("${app.video.api-secret}") String secret) {
        this.endpoint = signaling.replaceFirst("^wss://", "https://");
        this.key = key;
        this.secret = secret;
    }

    public void open(String room) {
        call(
                "CreateRoom",
                Json.object(
                        "name",
                        room,
                        "empty_timeout",
                        300,
                        "departure_timeout",
                        120,
                        "max_participants",
                        2));
    }

    public void close(String room) {
        call("DeleteRoom", Json.object("room", room));
    }

    private void call(String method, JsonNode body) {
        Problem.require(
                endpoint.startsWith("https://") && !key.isBlank() && !secret.isBlank(),
                503,
                "VIDEO_UNAVAILABLE");
        try {
            var now = Instant.now();
            var jwt =
                    new SignedJWT(
                            new JWSHeader(JWSAlgorithm.HS256),
                            new JWTClaimsSet.Builder()
                                    .issuer(key)
                                    .issueTime(Date.from(now))
                                    .notBeforeTime(Date.from(now.minusSeconds(5)))
                                    .expirationTime(Date.from(now.plusSeconds(30)))
                                    .jwtID(UUID.randomUUID().toString())
                                    .claim("video", Map.of("roomCreate", true))
                                    .build());
            jwt.sign(new MACSigner(secret));
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    endpoint
                                                            + "/twirp/livekit.RoomService/"
                                                            + method))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Authorization", "Bearer " + jwt.serialize())
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (method.equals("DeleteRoom") && response.statusCode() == 404) return;
            Problem.require(response.statusCode() == 200, 503, "VIDEO_UNAVAILABLE");
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Problem(503, "VIDEO_UNAVAILABLE");
        }
    }
}
