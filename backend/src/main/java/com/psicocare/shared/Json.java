package com.psicocare.shared;

import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Json {
    public static final ObjectMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    private Json() {}

    public static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new Problem(422, "INVALID_JSON");
        }
    }

    public static ObjectNode object(Object... pairs) {
        var result = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2)
            result.set(pairs[i].toString(), MAPPER.valueToTree(pairs[i + 1]));
        return result;
    }

    public static String text(JsonNode n, String field) {
        return n.path(field).asText("");
    }

    public static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(text(n, field));
        } catch (Exception e) {
            throw new Problem(422, "INVALID_UUID");
        }
    }

    public static byte[] hash(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(JsonNode n) {
        if (n.isObject()) {
            var o = MAPPER.createObjectNode();
            var keys = new TreeSet<String>();
            n.propertyNames().forEach(keys::add);
            keys.forEach(k -> o.set(k, parse(canonical(n.get(k)))));
            return o.toString();
        }
        if (n.isArray()) {
            var a = MAPPER.createArrayNode();
            n.forEach(v -> a.add(parse(canonical(v))));
            return a.toString();
        }
        return n.toString();
    }

    public static JsonNode page(List<? extends JsonNode> items) {
        return object("items", items, "nextCursor", null);
    }
}
