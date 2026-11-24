package io.github.madaai.idlestow.core.settings;

import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.Sql;

import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Persists {@link Settings} in the {@code settings} table and notifies listeners on change. */
public final class SettingsStore {
    private final Database db;
    private final Clock clock;
    private volatile Settings current;
    private final List<Consumer<Settings>> listeners = new CopyOnWriteArrayList<>();

    public SettingsStore(Database db, Clock clock) throws SQLException {
        this.db = db;
        this.clock = clock;
        this.current = load();
    }

    private Settings load() throws SQLException {
        Map<String, String> raw = new HashMap<>();
        db.read(c -> {
            for (String[] kv : Sql.list(c, "SELECT key, value FROM settings",
                    rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                raw.put(kv[0], kv[1]);
            }
            return null;
        });
        return Settings.fromMap(raw);
    }

    public Settings get() {
        return current;
    }

    /** Validates and persists {@code s} atomically, then notifies listeners. */
    public synchronized void save(Settings s) throws SQLException {
        Settings validated = Settings.fromMap(s.toMap());
        String now = clock.instant().toString();
        db.tx(c -> {
            for (Map.Entry<String, String> e : validated.toMap().entrySet()) {
                Sql.update(c, "INSERT INTO settings(key,value,updated_utc) VALUES(?,?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_utc=excluded.updated_utc",
                        e.getKey(), e.getValue(), now);
            }
        });
        current = validated;
        for (Consumer<Settings> l : listeners) l.accept(validated);
    }

    public void update(String key, String value) throws SQLException {
        save(current.with(key, value));
    }

    public void addListener(Consumer<Settings> l) {
        listeners.add(l);
    }
}
