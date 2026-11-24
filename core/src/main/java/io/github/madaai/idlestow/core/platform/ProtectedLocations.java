package io.github.madaai.idlestow.core.platform;

import java.nio.file.Path;
import java.util.List;

/** Operating-system locations that IdleStow must never optimize, regardless of policy. */
public interface ProtectedLocations {

    /** Absolute, normalized directories whose whole subtree is protected. */
    List<Path> protectedDirectories();

    /** Lower-case directory names that are protected wherever they appear (e.g. {@code $recycle.bin}). */
    List<String> protectedDirectoryNames();
}
