package com.psicocare.shared;

import tools.jackson.databind.JsonNode;

import java.util.*;

public record Command(
        String method,
        String path,
        String tag,
        String permission,
        Map<String, String> vars,
        Map<String, String> query,
        JsonNode body,
        Actor actor,
        int expectedVersion,
        String verifiedEmail,
        UUID idempotencyKey) {
    public UUID id(String key) {
        try {
            return UUID.fromString(vars.get(key));
        } catch (Exception e) {
            throw new Problem(422, "INVALID_UUID");
        }
    }

    public String tail() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public boolean isGet() {
        return method.equals("GET");
    }

    public boolean isPost() {
        return method.equals("POST");
    }

    public int limit() {
        try {
            int n = Integer.parseInt(query.getOrDefault("limit", "25"));
            Problem.require(n >= 1 && n <= 100, 422, "INVALID_LIMIT");
            return n;
        } catch (NumberFormatException e) {
            throw new Problem(422, "INVALID_LIMIT");
        }
    }

    public int offset() {
        try {
            int n =
                    query.containsKey("cursor")
                            ? Integer.parseInt(
                                    new String(
                                            Base64.getUrlDecoder().decode(query.get("cursor")),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            : 0;
            Problem.require(n >= 0 && n <= 100000, 422, "INVALID_CURSOR");
            return n;
        } catch (Exception e) {
            throw new Problem(422, "INVALID_CURSOR");
        }
    }

    public JsonNode page(List<? extends JsonNode> values) {
        int offset = offset();
        Problem.require(offset >= 0 && offset <= 100000, 422, "INVALID_CURSOR");
        int limit = limit();
        var result = values.stream().skip(offset).limit(limit).toList();
        return Json.object(
                "items",
                result,
                "nextCursor",
                values.size() > offset + limit
                        ? Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        Integer.toString(offset + limit)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null);
    }

    public JsonNode sqlPage(List<? extends JsonNode> values) {
        Problem.require(offset() >= 0 && offset() <= 100000, 422, "INVALID_CURSOR");
        return Json.object(
                "items",
                values.stream().limit(limit()).toList(),
                "nextCursor",
                values.size() > limit()
                        ? Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        Integer.toString(offset() + limit())
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null);
    }

    public void version(Map<String, Object> row) {
        Problem.require(expectedVersion == Store.version(row), 412, "STALE_VERSION");
    }
}
