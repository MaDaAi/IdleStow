package io.github.madaai.idlestow.core.platform;

import java.util.Objects;

/** Stable identity of a file independent of its path (volume + file id / inode). */
public record FileKey(String volumeId, String fileId) {
    public FileKey {
        Objects.requireNonNull(volumeId, "volumeId");
        Objects.requireNonNull(fileId, "fileId");
    }

    /** Serialized form stored in the database, e.g. {@code "1A2B3C4D:00010000000123AB"}. */
    public String asString() {
        return volumeId + ":" + fileId;
    }

    public static FileKey parse(String s) {
        int i = s.lastIndexOf(':');
        if (i <= 0 || i == s.length() - 1) throw new IllegalArgumentException("Invalid file key: " + s);
        return new FileKey(s.substring(0, i), s.substring(i + 1));
    }
}
