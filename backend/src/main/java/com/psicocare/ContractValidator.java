package com.psicocare;

import com.psicocare.shared.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Component
public class ContractValidator {
    private final JsonNode schemas;

    public ContractValidator() throws java.io.IOException {
        schemas = Json.MAPPER.readTree(getClass().getResourceAsStream("/contracts/schemas.json"));
    }

    public void validate(String name, JsonNode data) {
        if (!name.isBlank()) validateNode(schemas.path(name), data);
    }

    private void validateNode(JsonNode s, JsonNode n) {
        if (s.has("anyOf")) {
            for (JsonNode choice : s.get("anyOf")) {
                try {
                    validateNode(choice, n);
                    return;
                } catch (Problem invalid) {
                    /* next alternative */
                }
            }
            throw new Problem(422, "VALIDATION_FAILED");
        }
        if (s.has("$ref")) {
            validateNode(
                    schemas.path(s.get("$ref").asText().replace("#/components/schemas/", "")), n);
            return;
        }
        String type = s.path("type").isTextual() ? s.path("type").asText() : "";
        if (s.path("type").isArray()) {
            if (n.isNull() && s.get("type").toString().contains("null")) return;
            type = s.get("type").get(0).asText();
        }
        boolean ok =
                switch (type) {
                    case "object" -> n.isObject();
                    case "array" -> n.isArray();
                    case "integer" -> n.isIntegralNumber() && n.canConvertToLong();
                    case "number" -> n.isNumber();
                    case "string" -> n.isTextual();
                    case "boolean" -> n.isBoolean();
                    case "null" -> n.isNull();
                    default -> true;
                };
        Problem.require(ok, 422, "VALIDATION_FAILED");
        if (s.has("enum")) {
            boolean found = false;
            for (JsonNode v : s.get("enum")) if (v.equals(n)) found = true;
            Problem.require(found, 422, "VALIDATION_FAILED");
        }
        if (n.isObject()) {
            for (JsonNode required : s.path("required"))
                Problem.require(n.hasNonNull(required.asText()), 422, "REQUIRED_FIELD_MISSING");
            n.properties()
                    .forEach(
                            f -> {
                                if (s.path("properties").has(f.getKey()))
                                    validateNode(
                                            s.path("properties").get(f.getKey()), f.getValue());
                                else
                                    Problem.require(
                                            !s.has("additionalProperties")
                                                    || s.path("additionalProperties")
                                                            .asBoolean(true),
                                            422,
                                            "UNKNOWN_FIELD");
                            });
        }
        if (n.isArray()) {
            if (s.path("uniqueItems").asBoolean()) {
                var seen = new HashSet<JsonNode>();
                for (JsonNode value : n) Problem.require(seen.add(value), 422, "VALIDATION_FAILED");
            }
            Problem.require(
                    n.size() >= s.path("minItems").asInt(0)
                            && n.size() <= s.path("maxItems").asInt(1000),
                    422,
                    "VALIDATION_FAILED");
            n.forEach(v -> validateNode(s.path("items"), v));
        }
        if (n.isNumber())
            Problem.require(
                    n.decimalValue()
                                            .compareTo(
                                                    s.path("minimum").isMissingNode()
                                                            ? new java.math.BigDecimal(
                                                                    Long.MIN_VALUE)
                                                            : s.get("minimum").decimalValue())
                                    >= 0
                            && n.decimalValue()
                                            .compareTo(
                                                    s.path("maximum").isMissingNode()
                                                            ? new java.math.BigDecimal(
                                                                    Long.MAX_VALUE)
                                                            : s.get("maximum").decimalValue())
                                    <= 0,
                    422,
                    "VALIDATION_FAILED");
        if (n.isTextual()) {
            String value = n.asText();
            Problem.require(
                    value.length() >= s.path("minLength").asInt(0)
                            && value.length() <= s.path("maxLength").asInt(100000),
                    422,
                    "VALIDATION_FAILED");
            if (s.has("pattern"))
                Problem.require(value.matches(s.get("pattern").asText()), 422, "VALIDATION_FAILED");
            try {
                switch (s.path("format").asText()) {
                    case "uuid" -> UUID.fromString(value);
                    case "date-time" -> OffsetDateTime.parse(value);
                    case "date" -> LocalDate.parse(value);
                    case "email" ->
                            Problem.require(
                                    value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"),
                                    422,
                                    "VALIDATION_FAILED");
                    case "uri" ->
                            Problem.require(
                                    java.net.URI.create(value).isAbsolute(),
                                    422,
                                    "VALIDATION_FAILED");
                    default -> {}
                }
            } catch (Problem e) {
                throw e;
            } catch (Exception e) {
                throw new Problem(422, "VALIDATION_FAILED");
            }
        }
    }
}
