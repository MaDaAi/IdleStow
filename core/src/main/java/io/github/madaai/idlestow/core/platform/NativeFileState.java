package io.github.madaai.idlestow.core.platform;

/**
 * Low-level state of a single file as reported by the operating system.
 *
 * @param fileKey          stable identity, or null when the platform cannot provide one
 * @param allocatedSize    physical bytes allocated on disk, or -1 when unknown
 * @param allocationExact  true when {@code allocatedSize} comes from a reliable OS API
 * @param compressed       filesystem-native compression is active
 * @param encrypted        filesystem encryption (e.g. EFS) is active
 * @param sparse           sparse file
 * @param reparsePoint     reparse point / symlink / junction / special file
 * @param cloudPlaceholder online-only / recall-on-access placeholder (OneDrive etc.) — never hydrate
 * @param offline          offline attribute set
 * @param system           system attribute set
 * @param linkCount        number of hard links, or -1 when unknown
 * @param alternateStreams number of named alternate data streams, or -1 when unknown
 */
public record NativeFileState(
        FileKey fileKey,
        long allocatedSize,
        boolean allocationExact,
        boolean compressed,
        boolean encrypted,
        boolean sparse,
        boolean reparsePoint,
        boolean cloudPlaceholder,
        boolean offline,
        boolean system,
        int linkCount,
        int alternateStreams) {
}
