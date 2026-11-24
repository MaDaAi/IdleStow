package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.model.Enums.Method;

import java.nio.file.Path;
import java.util.List;

/**
 * What an optimization would change, computed before anything is authorized.
 * Lists both the files that will change and every descendant that will stay untouched (with reasons),
 * so folder-level selection never hides active, protected or ineligible files.
 */
public record PlanPreview(
        Method method,
        List<Item> willChange,
        List<Item> untouched,
        long estimatedSavings,
        List<String> warnings,
        boolean changesAccessibility,
        String recoveryNote) {

    public record Item(long itemId, Path path, long size, long estimatedSavings, String activity, String confidence,
                       String reason) { }

    public boolean empty() {
        return willChange.isEmpty();
    }
}
