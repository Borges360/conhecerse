package com.psicocare.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Component
public class Store {
    public final JdbcTemplate jdbc;

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int exec(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rs = rows(sql, args);
        if (rs.isEmpty()) throw Problem.notFound();
        return rs.getFirst();
    }

    public Optional<Map<String, Object>> optional(String sql, Object... args) {
        return rows(sql, args).stream().findFirst();
    }

    public boolean exists(String sql, Object... args) {
        return !rows(sql, args).isEmpty();
    }

    public static UUID id(Map<String, Object> r, String k) {
        return UUID.fromString(r.get(k).toString());
    }

    public static String str(Map<String, Object> r, String k) {
        return Objects.toString(r.get(k), "");
    }

    public static int version(Map<String, Object> r) {
        return ((Number) r.getOrDefault("version", 1)).intValue();
    }

    public static JsonNode dto(Map<String, Object> r, String... keys) {
        var o = Json.MAPPER.createObjectNode();
        for (String key : keys) {
            var v = r.get(key);
            if (v == null) continue;
            var name = new StringBuilder();
            boolean up = false;
            for (char c : key.toCharArray()) {
                if (c == '_') {
                    up = true;
                    continue;
                }
                name.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
            if (v instanceof java.sql.Timestamp ts) v = ts.toInstant().toString();
            if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            if (v instanceof java.time.OffsetDateTime d) v = d.toInstant().toString();
            o.set(name.toString(), Json.MAPPER.valueToTree(v));
        }
        return o;
    }
}
