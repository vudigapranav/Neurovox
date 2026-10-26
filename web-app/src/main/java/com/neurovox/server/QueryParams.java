package com.neurovox.server;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a raw query string strictly: every name must be expected, and none may repeat,
 * so a malformed request fails clearly instead of being half understood.
 */
final class QueryParams {

    private final Map<String, String> values;

    private QueryParams(Map<String, String> values) {
        this.values = values;
    }

    static QueryParams parse(String rawQuery, Set<String> allowed) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return new QueryParams(values);
        }
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name;
            String value;
            try {
                name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw HttpError.badRequest("invalid_query", "The address contains a badly encoded parameter.");
            }
            if (!allowed.contains(name)) {
                throw HttpError.badRequest("unknown_parameter", "Unknown parameter \"" + name + "\". Expected: " + String.join(", ", allowed) + ".");
            }
            if (values.putIfAbsent(name, value) != null) {
                throw HttpError.badRequest("duplicate_parameter", "The parameter \"" + name + "\" was given more than once.");
            }
        }
        return new QueryParams(values);
    }

    Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    String require(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw HttpError.badRequest("missing_parameter", "The parameter \"" + name + "\" is required.");
        }
        return value;
    }
}
