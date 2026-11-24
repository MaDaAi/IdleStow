package io.github.madaai.idlestow.core.testsupport;

import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;

import java.nio.file.Path;
import java.util.Set;

/** Builders for in-memory {@link IndexedItem} values. */
public final class Items {
    public static final long DAY = 86_400_000L;

    private Items() { }

    /** A file item with the given timestamps; first indexed long ago. */
    public static IndexedItem file(Path path, long createdMs, long modifiedMs, long trustedAccessMs,
                                   String firstIndexedUtc) {
        String name = path.getFileName().toString();
        return new IndexedItem(1, 1, path, name, name, "txt", "Documents", false, true, false, "VOL:1", "VOL",
                1000, 4096, true, createdMs, modifiedMs, trustedAccessMs, trustedAccessMs, firstIndexedUtc,
                firstIndexedUtc, 1, 0, Set.of(), false, OptStatus.NONE, null, null);
    }
}
