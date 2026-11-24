package io.github.madaai.idlestow.core.index;

import java.nio.file.Path;
import java.util.Set;

/** Metadata gathered for one filesystem entry during a scan (metadata only — contents are never read). */
public record ScannedEntry(
        Path path,
        String relPath,
        String parentKey,
        String name,
        String extension,
        String category,
        boolean directory,
        boolean special,
        String fileKey,
        String volumeId,
        long logicalSize,
        long physicalSize,
        boolean physicalExact,
        long createdMs,
        long modifiedMs,
        long accessedMs,
        int linkCount,
        int altStreams,
        Set<String> attrs,
        boolean nativeCompressed) {
}
