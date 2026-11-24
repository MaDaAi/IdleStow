package io.github.madaai.idlestow.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON helper for snapshots, journal data and manifests. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() { }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize", e);
        }
    }

    public static String pretty(Object o) {
        try {
            return PRETTY.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize", e);
        }
    }

    public static Map<String, Object> read(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    public static Map<String, Object> readFile(Path p) throws IOException {
        return MAPPER.readValue(p.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    /** Builds an ordered map from alternating key/value arguments. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return dflt;
    }
}
