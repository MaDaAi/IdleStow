package io.github.madaai.idlestow.app.util;

import io.github.madaai.idlestow.core.db.HistoryRepository.Entry;
import io.github.madaai.idlestow.core.util.Json;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CSV / JSON export of optimization history records (never includes file contents). */
public final class HistoryExport {
    public static final List<String> HEADER = List.of("id", "time_utc", "action", "outcome", "method", "origin",
            "mode", "path", "logical_size_bytes", "before_physical_bytes", "after_physical_bytes",
            "estimated_savings_bytes", "actual_savings_bytes", "verification", "recovery", "error");

    private HistoryExport() { }

    public static List<String> csvRow(Entry e) {
        List<String> r = new ArrayList<>();
        r.add(Long.toString(e.id()));
        r.add(e.atUtc());
        r.add(e.action());
        r.add(e.outcome());
        r.add(e.method());
        r.add(e.origin());
        r.add(e.authorizationMode());
        r.add(e.path());
        r.add(num(e.logicalSize()));
        r.add(num(e.beforePhysical()));
        r.add(num(e.afterPhysical()));
        r.add(num(e.estSavings()));
        r.add(num(e.actualSavings()));
        r.add(e.verification());
        r.add(e.recovery());
        r.add(e.error());
        return r;
    }

    private static String num(Long v) {
        return v == null ? "" : Long.toString(v);
    }

    public static Map<String, Object> toMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("time_utc", e.atUtc());
        m.put("action", e.action());
        m.put("outcome", e.outcome());
        m.put("method", e.method());
        m.put("origin", e.origin());
        m.put("mode", e.authorizationMode());
        m.put("path", e.path());
        m.put("logical_size_bytes", e.logicalSize());
        m.put("before_physical_bytes", e.beforePhysical());
        m.put("after_physical_bytes", e.afterPhysical());
        m.put("estimated_savings_bytes", e.estSavings());
        m.put("actual_savings_bytes", e.actualSavings());
        m.put("verification", e.verification());
        m.put("recovery", e.recovery());
        m.put("error", e.error());
        return m;
    }

    /** Writes UTF-8 with a BOM so spreadsheet programs detect non-ASCII file names correctly. */
    public static void writeCsv(Path target, List<Entry> entries) throws IOException {
        try (Writer w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write('﻿');
            List<List<String>> rows = new ArrayList<>(entries.size());
            for (Entry e : entries) rows.add(csvRow(e));
            CsvWriter.write(w, HEADER, rows);
        }
    }

    public static void writeJson(Path target, List<Entry> entries) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>(entries.size());
        for (Entry e : entries) list.add(toMap(e));
        Files.writeString(target, Json.pretty(Json.map("exported_records", list.size(), "records", list)),
                StandardCharsets.UTF_8);
    }
}
