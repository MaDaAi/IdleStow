package io.github.madaai.idlestow.app.util;

import io.github.madaai.idlestow.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small per-installation UI state (window geometry, last view, hints already shown) kept as JSON next to the
 * database. Losing or corrupting this file is harmless: defaults are used.
 */
public final class UiPrefs {
    private final Path file;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public UiPrefs(Path file) {
        this.file = file;
        load();
    }

    public static UiPrefs forDataDir(Path dataDir) {
        return new UiPrefs(dataDir.resolve("ui-state.json"));
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                values.putAll(Json.readFile(file));
            }
        } catch (IOException | RuntimeException e) {
            values.clear();
        }
    }

    public synchronized double getDouble(String key, double dflt) {
        Object v = values.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public synchronized boolean getBool(String key, boolean dflt) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    public synchronized String getString(String key, String dflt) {
        Object v = values.get(key);
        return v instanceof String s ? s : dflt;
    }

    public synchronized void put(String key, Object value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
    }

    public synchronized boolean has(String key) {
        return values.containsKey(key);
    }

    /** Persists atomically; failures are ignored because this state is optional. */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(values), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // optional state
        }
    }
}
