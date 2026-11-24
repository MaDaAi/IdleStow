package io.github.madaai.idlestow.core.platform;

/**
 * Information about the volume (filesystem) holding a path.
 *
 * @param volumeId               stable identifier (e.g. NTFS volume serial number in hex), never null
 * @param rootPath               mount point / drive root, e.g. {@code C:\}
 * @param fileSystemType         e.g. {@code NTFS}, {@code ext4}, {@code apfs}
 * @param nativeCompression      whether filesystem-native transparent compression is usable on this volume
 * @param nativeCompressionNote  human readable reason when not usable (may be empty)
 * @param clusterSize            allocation unit in bytes, or -1 when unknown
 * @param readOnly               volume is read only
 * @param remote                 network / remote volume
 * @param accessTracking         reliability of last-access timestamps on this volume
 * @param accessTrackingNote     explanation shown to users
 */
public record VolumeInfo(
        String volumeId,
        String rootPath,
        String fileSystemType,
        boolean nativeCompression,
        String nativeCompressionNote,
        long clusterSize,
        boolean readOnly,
        boolean remote,
        AccessTracking accessTracking,
        String accessTrackingNote) {

    /**
     * ENABLED: last-access times are updated (possibly with ~1h granularity).
     * COARSE: updated with day-level granularity (e.g. Linux relatime) — usable for day thresholds.
     * DISABLED: not updated. UNKNOWN: cannot be determined.
     */
    public enum AccessTracking { ENABLED, COARSE, DISABLED, UNKNOWN }
}
