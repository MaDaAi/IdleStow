package io.github.madaai.idlestow.core.policy;

/**
 * One scoped policy row. Null fields mean "inherit from the less specific level".
 *
 * <p>Scopes: {@code GLOBAL} (target "*"), {@code ROOT} (target = root id), {@code DIRECTORY}
 * (target = directory path key, applies to the subtree), {@code FILE} (target = file path key),
 * {@code EXTENSION} (target = lower-case extension without dot).
 *
 * @param allowedMethods comma separated {@link io.github.madaai.idlestow.core.model.Enums.Method} names, or null
 */
public record PolicyRule(
        long id,
        Scope scope,
        String target,
        Integer inactivityDays,
        Boolean neverOptimize,
        Boolean archival,
        String minConfidence,
        String allowedMethods) {

    public enum Scope { GLOBAL, ROOT, DIRECTORY, EXTENSION, FILE }
}
