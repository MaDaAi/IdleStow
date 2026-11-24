package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.HistoryExport;
import io.github.madaai.idlestow.core.db.HistoryRepository.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryExportTest {
    private static Entry entry(String path, String error) {
        return new Entry(1, 2L, "2026-01-02T03:04:05Z", path, "key", "OPTIMIZE", "NATIVE", "MANUAL", "ASK", "p",
                1000L, 1000L, 400L, 700L, 600L, "ok", "reversible", "SUCCEEDED", error);
    }

    @Test
    void csvRowMatchesHeaderWidth() {
        assertEquals(HistoryExport.HEADER.size(), HistoryExport.csvRow(entry("a", null)).size());
    }

    @Test
    void csvFileQuotesAwkwardValues(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("h.csv");
        HistoryExport.writeCsv(f, List.of(entry("C:\\Docs\\a,b \"x\".txt", "line1\nline2 \u00e9")));
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFFid,time_utc"));
        assertTrue(text.contains("\"C:\\Docs\\a,b \"\"x\"\".txt\""));
        assertTrue(text.contains("\"line1\nline2 \u00e9\""));
    }

    @Test
    void jsonFileContainsRecords(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("h.json");
        HistoryExport.writeJson(f, List.of(entry("a.txt", null)));
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"actual_savings_bytes\" : 600"));
        assertTrue(text.contains("a.txt"));
    }
}
