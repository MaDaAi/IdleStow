package io.github.madaai.idlestow.core.platform;

import java.io.IOException;
import java.nio.file.Path;

/** Read-only inspection of files and volumes. Never modifies anything, never hydrates cloud placeholders. */
public interface FileInspector {

    /** Volume information for the volume containing {@code path}. */
    VolumeInfo volumeOf(Path path) throws IOException;

    /** Native state of a regular file. Must not follow symbolic links / reparse points. */
    NativeFileState inspect(Path path) throws IOException;

    /**
     * Whether another process currently holds the file open in a way that makes mutation unsafe.
     * Returns {@code null} when this cannot be determined on the platform.
     */
    Boolean isLockedByOtherProcess(Path path);
}
