package io.github.madaai.idlestow.core.model;

import io.github.madaai.idlestow.core.model.Enums.OptStatus;

import java.nio.file.Path;
import java.util.Set;

/** A file or directory in the persistent index. Timestamps are epoch millis (0/null when unknown). */
public record IndexedItem(
        long id,
        long rootId,
        Path path,
        String relPath,
        String name,
        String extension,
        String category,
        boolean directory,
        boolean present,
        boolean special,
        String fileKey,
        String volumeId,
        long logicalSize,
        long physicalSize,
        boolean physicalExact,
        long createdMs,
        long modifiedMs,
        long accessedMs,
        long trustedAccessMs,
        String firstIndexedUtc,
        String lastScannedUtc,
        int linkCount,
        int altStreams,
        Set<String> attrFlags,
        boolean nativeCompressed,
        OptStatus optStatus,
        Estimate estimate,
        String error) {

    public static final String ATTR_COMPRESSED = "compressed";
    public static final String ATTR_ENCRYPTED = "encrypted";
    public static final String ATTR_SPARSE = "sparse";
    public static final String ATTR_REPARSE = "reparse";
    public static final String ATTR_CLOUD = "cloud";
    public static final String ATTR_OFFLINE = "offline";
    public static final String ATTR_SYSTEM = "system";
    public static final String ATTR_READONLY = "readonly";

    public boolean has(String attr) {
        return attrFlags.contains(attr);
    }

    /** Compressibility estimate produced by the Python worker. Always an estimate, never a measurement. */
    public record Estimate(Double zipRatio, Long ntfsSavings, Long zipSavings, Long zstdSavings,
                           String detectedFormat, String basis, long forSize, long forModifiedMs) {

        /** An estimate is only valid for the exact size/mtime it was computed for. */
        public boolean validFor(long size, long modifiedMs) {
            return forSize == size && forModifiedMs == modifiedMs;
        }
    }
}
